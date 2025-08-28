// SecretCodeGuesser.java
// Enhanced version with character exhaustion tracking
// Prevents using characters that have frequency = 0 remaining

public class SecretCodeGuesser {

    // Allowed letters (fixed order)
    private static final char[] ALPH = {'B', 'A', 'C', 'X', 'I', 'U'};
    private static final int ALPH_SZ = ALPH.length;

    // Verbose logging toggle (set false for quiet runs)
    private static final boolean LOG = true;

    // Harness instance (provided by assignment)
    private final SecretCode harness = new SecretCode();

    // Local instrumentation
    private int localGuessCount = 0;
    private long startTimeMs;

    // ---------------- Entry point ----------------
    public void start() {
        startTimeMs = System.currentTimeMillis();

        // 1) frequency measurement
        int[] counts = new int[ALPH_SZ];

        // detect length and capture B-count in counts[ indexOf('B') ]
        int N = detectLengthAndSetBCount(counts);
        if (N > 18) {
            System.out.println("ERROR: Secret code length exceeds 18. Aborting.");
            return;
        }
        log("Detected length N = " + N + "  (B count captured = " + counts[alphaIndex('B')] + ")");

        // measure other letters (one all-same guess per letter except B)
        for (int i = 0; i < ALPH_SZ; i++) {
            if (ALPH[i] == 'B') continue;
            counts[i] = callGuess(repeatChar(ALPH[i], N));
        }
        log("Letter counts: " + countsToString(counts));

        // sanity check
        int sum = 0;
        for (int v : counts) sum += v;
        if (sum != N) {
            System.out.println("ERROR: counts sum != N. Aborting. (counts sum = " + sum + ", N=" + N + ")");
            return;
        }

        // Step: forced-fill if only one letter present across entire string
        int nonZero = -1, numNonZero = 0;
        for (int i = 0; i < ALPH_SZ; i++) {
            if (counts[i] > 0) { nonZero = i; numNonZero++; }
        }
        if (numNonZero == 1) {
            // only one letter occurs -> fill and finish
            char[] secretArr = new char[N];
            for (int j = 0; j < N; j++) secretArr[j] = ALPH[nonZero];
            String secret = new String(secretArr);
            long elapsed = System.currentTimeMillis() - startTimeMs;
            System.out.println("Secret found (single-letter fill): " + secret);
            System.out.println("Total guesses: " + localGuessCount);
            System.out.println("Elapsed ms   : " + elapsed);
            return;
        }

        // 2) Set up working structures
        int[] remaining = counts.clone();        // remaining count to place for each letter
        int[] posMask = new int[ALPH_SZ];        // bitmask of possible positions for each letter
        int fullMask = (N >= 31) ? ~0 : ((1 << N) - 1);
        for (int i = 0; i < ALPH_SZ; i++) posMask[i] = fullMask;

        boolean[] confirmed = new boolean[N];    // which positions are confirmed
        char[] candidate = new char[N];          // working candidate (tentative values)
        for (int i = 0; i < N; i++) candidate[i] = ALPH[0];

        // 3) initial candidate: blocks in descending frequency (frequency-priority)
        buildInitialCandidate(candidate, counts);
        int baselineMatches = callGuess(new String(candidate));
        log("Initial candidate: " + new String(candidate) + "  matches=" + baselineMatches);

        // 5) single-position refinement with global priority
        if (!allConfirmed(confirmed)) {
            log("Falling back to single-position refinement (global priority)...");
            singlePositionRefinement(confirmed, candidate, remaining, posMask, baselineMatches);
        }

        // 6) final report: if confirmed, skip redundant final guess
        String secret = new String(candidate);
        int finalMatches;
        if (allConfirmed(confirmed)) {
            finalMatches = N;
        } else {
            finalMatches = callGuess(secret);
        }
        System.out.println("Secret found : " + secret);
        System.out.println("Final matches : " + finalMatches + " (expected " + N + ")");
        System.out.println("Total guesses: " + localGuessCount);
        System.out.println("Time taken: " + (System.currentTimeMillis() - startTimeMs) + " ms");
    }

    // ---------------- detect length and capture B-count ----------------
    private int detectLengthAndSetBCount(int[] counts) {
        int bIdx = alphaIndex('B');
        for (int k = 1; k <= 18; k++) {
            int r = callGuess(repeatChar('B', k));
            if (r != -2) {
                counts[bIdx] = r; // number of B's in the secret
                return k;
            }
        }
        return 19; // signal error if not found
    }

    // ---------------- Enhanced single-position refinement with exhaustion tracking ----------------
    private void singlePositionRefinement(boolean[] confirmed, char[] candidate,
                                          int[] remaining, int[] posMask, int baseline) {
        int N = candidate.length;
        int baselineMatches = baseline;

        // global priority: indices sorted by remaining descending (recomputed when remaining changes)
        int[] globalPriority = orderByCountsDesc(remaining);

        while (!allConfirmed(confirmed)) {

            // Log current remaining counts for debugging
            log("Current remaining: " + countsToString(remaining));

            // forced-fill optimization: if some letter's remaining equals number of open slots
            int open = 0;
            for (int i = 0; i < N; i++) if (!confirmed[i]) open++;
            int forcedIdx = -1;
            for (int i = 0; i < ALPH_SZ; i++) {
                if (remaining[i] == open && open > 0) { forcedIdx = i; break; }
            }
            if (forcedIdx != -1) {
                log("Forced-fill: filling " + open + " unknown(s) with '" + ALPH[forcedIdx] + "' in ONE guess.");
                for (int p = 0; p < N; p++) {
                    if (!confirmed[p]) {
                        confirmed[p] = true;
                        candidate[p] = ALPH[forcedIdx];
                        if (remaining[forcedIdx] > 0) remaining[forcedIdx]--;
                        int bit = 1 << p;
                        for (int k = 0; k < ALPH_SZ; k++) if (k != forcedIdx) posMask[k] &= ~bit;
                    }
                }
                baselineMatches = callGuess(new String(candidate));
                log("After forced-fill guess, m0 = " + baselineMatches);
                // recompute priority
                globalPriority = orderByCountsDesc(remaining);
                continue;
            }

            boolean progressed = false;

            // Try to confirm one position per outer loop iteration (keeps changes manageable)
            for (int pos = 0; pos < N && !progressed; pos++) {
                if (confirmed[pos]) continue;

                // Build try-candidates using global priority (filtering by posMask and remaining > 0)
                int[] tryCandidates = new int[ALPH_SZ];
                int t = 0;
                for (int gi = 0; gi < ALPH_SZ; gi++) {
                    int li = globalPriority[gi];

                    // KEY ENHANCEMENT: Skip characters with remaining <= 0
                    if (remaining[li] <= 0) {
                        log("Skipping '" + ALPH[li] + "' at pos " + pos + " - exhausted (remaining=" + remaining[li] + ")");
                        continue;
                    }

                    if (((posMask[li] >> pos) & 1) == 0) {
                        log("Skipping '" + ALPH[li] + "' at pos " + pos + " - eliminated by position mask");
                        continue;
                    }

                    if (ALPH[li] == candidate[pos]) {
                        log("Skipping '" + ALPH[li] + "' at pos " + pos + " - already current tentative char");
                        continue; // skip current tentative char
                    }

                    tryCandidates[t++] = li;
                }

                if (t == 0) {
                    log("No valid candidates to try at position " + pos);
                    continue; // nothing to try here
                }

                // Try candidates in priority order
                for (int k = 0; k < t && !progressed; k++) {
                    int li = tryCandidates[k];
                    char test = ALPH[li];

                    // Double-check that we still have remaining characters before trying
                    if (remaining[li] <= 0) {
                        log("Skipping exhausted character '" + test + "' at pos " + pos + " (remaining=" + remaining[li] + ")");
                        continue;
                    }

                    char[] temp = candidate.clone();
                    temp[pos] = test;
                    int mt = callGuess(new String(temp));
                    int delta = mt - baselineMatches;

                    if (delta == 1) {
                        // new letter is correct at pos
                        candidate[pos] = test;
                        confirmed[pos] = true;
                        if (remaining[li] > 0) remaining[li]--;
                        int bit = 1 << pos;
                        for (int z = 0; z < ALPH_SZ; z++) if (z != li) posMask[z] &= ~bit;
                        baselineMatches = mt; // adopt new baseline
                        log("Confirmed pos " + pos + " = '" + test + "' (+1). m0=" + baselineMatches + ", remaining[" + ALPH[li] + "]=" + remaining[li]);
                        progressed = true;
                        // update global priority since remaining changed
                        globalPriority = orderByCountsDesc(remaining);
                        break;
                    } else if (delta == -1) {
                        // original candidate[pos] was correct
                        int origIdx = alphaIndex(candidate[pos]);
                        if (origIdx >= 0 && remaining[origIdx] > 0) remaining[origIdx]--;
                        confirmed[pos] = true;
                        int bit = 1 << pos;
                        for (int z = 0; z < ALPH_SZ; z++) if (z != origIdx) posMask[z] &= ~bit;
                        log("Confirmed pos " + pos + " = '" + candidate[pos] + "' (-1). m0 stays " + baselineMatches + ", remaining[" + ALPH[origIdx] + "]=" + remaining[origIdx]);
                        progressed = true;
                        // update global priority since remaining changed
                        globalPriority = orderByCountsDesc(remaining);
                        break;
                    } else {
                        // delta == 0: eliminate li at pos
                        posMask[li] &= ~(1 << pos);
                        log("Eliminated '" + test + "' at pos " + pos + " (0).");
                    }
                }
            }

            if (!progressed) {
                log("No progress in single-position refinement. Breaking.");
                break;
            }
        }
    }

    private boolean allConfirmed(boolean[] confirmed) {
        for (boolean b : confirmed) if (!b) return false;
        return true;
    }

    private void buildInitialCandidate(char[] cand, int[] counts) {
        int[] order = orderByCountsDesc(counts);
        int p = 0;
        for (int idx : order) {
            for (int k = 0; k < counts[idx] && p < cand.length; k++) cand[p++] = ALPH[idx];
        }
        while (p < cand.length) cand[p++] = ALPH[0];
    }

    private int[] orderByCountsDesc(int[] counts) {
        int[] idx = new int[ALPH_SZ];
        for (int i = 0; i < ALPH_SZ; i++) idx[i] = i;
        for (int i = 0; i < ALPH_SZ - 1; i++) {
            int best = i;
            for (int j = i + 1; j < ALPH_SZ; j++) if (counts[idx[j]] > counts[idx[best]]) best = j;
            int tmp = idx[i]; idx[i] = idx[best]; idx[best] = tmp;
        }
        return idx;
    }

    private String countsToString(int[] counts) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < ALPH_SZ; i++) {
            sb.append(ALPH[i]).append(":").append(counts[i]);
            if (i + 1 < ALPH_SZ) sb.append(", ");
        }
        sb.append("}");
        return sb.toString();
    }

    private String repeatChar(char c, int n) {
        char[] a = new char[n];
        for (int i = 0; i < n; i++) a[i] = c;
        return new String(a);
    }

    private int alphaIndex(char ch) {
        for (int i = 0; i < ALPH_SZ; i++) if (ALPH[i] == ch) return i;
        return -1;
    }

    private int callGuess(String s) {
        localGuessCount++;
        String upper = s.toUpperCase();
        int res = harness.guess(upper);
        if (LOG) System.out.println("GUESS#" + localGuessCount + " : \"" + upper + "\" -> " + res);
        // If res equals the length, finish guessing and exit
        if (res == upper.length()) {
            System.out.println("Secret found: " + upper);
            System.out.println("Total guesses: " + localGuessCount);
            long endTimeMs = System.currentTimeMillis();
            System.out.println("Time taken: " + (endTimeMs - startTimeMs) + " ms");
            System.exit(0);
        }
        return res;
    }

    private void log(String s) {
        if (LOG) System.out.println(s);
    }
}