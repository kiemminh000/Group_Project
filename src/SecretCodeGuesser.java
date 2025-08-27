// SecretCodeGuesser.java
// Readable, arrays-only solver for the assignment.
// - Reuses B-count from length detection
// - Early-exit for single-letter secrets
// - Frequency-priority initial candidate
// - Group/binary locating using absent-letter filler (safe fallback to single-bit probing)
// - Single-position refinement with forced-fill optimization
// - No Collections, no frameworks. Tutor-friendly style.

public class SecretCodeGuesser {

    // allowed letters (fixed order)
    private static final char[] ALPH = {'B', 'A', 'C', 'X', 'I', 'U'};
    private static final int ALPH_SZ = ALPH.length;

    // toggle detailed logging (set to false to reduce console output)
    private static final boolean LOG = true;

    // provided harness (do not modify)
    private final SecretCode harness = new SecretCode();

    // instrumentation
    private int localGuessCount = 0;
    private long startTimeMs = 0L;

    // safety: how many times to retry measurement if we encounter unexpected -2 responses
    private static final int MAX_RETRIES_ON_LENGTH_CONFLICT = 2;

    // ---------------- Entry point called by harness ----------------
    public void start() {
        startTimeMs = System.currentTimeMillis();

        // 1) Frequency measurement
        int[] counts = new int[ALPH_SZ];   // counts for B,A,C,X,I,U

        // detect length and at the same time capture number of 'B' characters
        int N = detectLengthAndSetBCount(counts);
        log("Detected length N = " + N + "  (B count captured = " + counts[alphaIndex('B')] + ")");

        // If the detect-length probe already said count of B == N, the secret is all 'B'
        if (counts[alphaIndex('B')] == N) {
            String secret = repeatChar('B', N);
            reportFinalSingleLetter(secret);
            return;
        }

        // 2) Measure counts for other letters; if unexpected -2 occurs, re-detect and retry
        boolean measurementOk = false;
        int retries = 0;

        while (!measurementOk && retries <= MAX_RETRIES_ON_LENGTH_CONFLICT) {
            boolean needRestart = false;

            for (int i = 0; i < ALPH_SZ; i++) {
                if (ALPH[i] == 'B') continue; // skip B, already measured

                int res = callGuess(repeatChar(ALPH[i], N));
                if (res == -2) {
                    // unexpected: harness says wrong length for this probe length -> re-detect
                    log("Warning: got -2 while probing '" + ALPH[i] + "' with length " + N + ". Re-detecting length.");
                    N = detectLengthAndSetBCount(counts);
                    log("Re-detected length N = " + N + "  (B count captured = " + counts[alphaIndex('B')] + ")");

                    // if now B occupies the whole secret, finish immediately
                    if (counts[alphaIndex('B')] == N) {
                        String secret = repeatChar('B', N);
                        reportFinalSingleLetter(secret);
                        return;
                    }

                    needRestart = true;
                    break;
                } else {
                    counts[i] = res;
                    // early-exit: if any letter count equals N, it's the full-secret letter
                    if (counts[i] == N) {
                        String secret = repeatChar(ALPH[i], N);
                        reportFinalSingleLetter(secret);
                        return;
                    }
                }
            } // end per-letter loop

            if (needRestart) {
                retries++;
                // clear non-B counts before re-measuring
                for (int k = 0; k < ALPH_SZ; k++) if (ALPH[k] != 'B') counts[k] = 0;
                continue; // retry measurement
            } else {
                measurementOk = true;
            }
        } // end measurement retry loop

        if (!measurementOk) {
            System.out.println("ERROR: measurement failed after retries. Aborting.");
            return;
        }

        log("Letter counts: " + countsToString(counts));

        // sanity check: counts sum must equal N
        int sum = 0;
        for (int v : counts) sum += v;
        if (sum != N) {
            System.out.println("ERROR: counts sum != N. Aborting. (counts sum = " + sum + ", N=" + N + ")");
            return;
        }

        // if only one non-zero letter remains, fill and finish
        int onlyIdx = -1;
        int nonZeroCount = 0;
        for (int i = 0; i < ALPH_SZ; i++) {
            if (counts[i] > 0) { onlyIdx = i; nonZeroCount++; }
        }
        if (nonZeroCount == 1) {
            String secret = repeatChar(ALPH[onlyIdx], N);
            reportFinalSingleLetter(secret);
            return;
        }

        // ---------------- Prepare working structures ----------------
        int[] remaining = counts.clone();     // remaining occurrences to place
        int[] posMask = new int[ALPH_SZ];     // candidate position bitmask for each letter
        int fullMask = (N >= 31) ? ~0 : ((1 << N) - 1);
        for (int i = 0; i < ALPH_SZ; i++) posMask[i] = fullMask;

        boolean[] confirmed = new boolean[N]; // confirmed positions
        char[] candidate = new char[N];       // working candidate string
        for (int i = 0; i < N; i++) candidate[i] = ALPH[0];

        // ---------------- Initial candidate ----------------
        buildInitialCandidate(candidate, counts);
        int baselineMatches = callGuess(new String(candidate));
        log("Initial candidate: " + new String(candidate) + "  matches=" + baselineMatches);

        // ---------------- Group locating using absent filler ----------------
        int absentIdx = findAbsentLetterIndex(counts);
        if (absentIdx >= 0) {
            log("Using absent letter '" + ALPH[absentIdx] + "' as filler for group locating.");
            for (int letterIdx = 0; letterIdx < ALPH_SZ; letterIdx++) {
                if (remaining[letterIdx] <= 0) continue;
                int unconfMask = getUnconfirmedMask(confirmed);
                int candidateMask = unconfMask & posMask[letterIdx];
                if (candidateMask == 0) continue;
                locatePositionsByBinarySplit(letterIdx, remaining[letterIdx], candidateMask, absentIdx,
                        confirmed, candidate, remaining, posMask);
            }
            // refresh baseline after group assignments
            baselineMatches = callGuess(new String(candidate));
            log("After group locating candidate: " + new String(candidate) + " matches=" + baselineMatches);
        }

        // ---------------- Single-position refinement ----------------
        if (!allConfirmed(confirmed)) {
            log("Falling back to single-position refinement (global priority)...");
            singlePositionRefinement(confirmed, candidate, remaining, posMask, baselineMatches);
        }

        // ---------------- Final report ----------------
        String secret = new String(candidate);
        int finalMatches;
        if (allConfirmed(confirmed)) {
            finalMatches = N;
        } else {
            finalMatches = callGuess(secret); // safety check
        }
        long elapsed = System.currentTimeMillis() - startTimeMs;
        System.out.println("Secret found : " + secret);
        System.out.println("Final matches : " + finalMatches + " (expected " + N + ")");
        System.out.println("Total guesses: " + localGuessCount);
        System.out.println("Elapsed ms   : " + elapsed);
    }

    // ---------------- detect length and set B-count ----------------
    // Runs "B", "BB", "BBB", ... until harness responds != -2. The response is the count of 'B's.
    private int detectLengthAndSetBCount(int[] counts) {
        int bIdx = alphaIndex('B');
        for (int k = 1; k <= 18; k++) {
            int r = callGuess(repeatChar('B', k));
            if (r != -2) {
                counts[bIdx] = r;
                return k;
            }
        }
        // fallback to max length if nothing detected (shouldn't happen per assignment)
        return 18;
    }

    // ---------------- group / binary locating (safe version) ----------------
    private void locatePositionsByBinarySplit(int letterIdx, int need, int candidateMask, int fillerIndex,
                                              boolean[] confirmed, char[] candidate, int[] remaining, int[] posMask) {
        if (need <= 0 || candidateMask == 0) return;

        int pop = Integer.bitCount(candidateMask);

        // if number of candidate positions equals needed count -> assign them
        if (pop == need) {
            assignMaskToLetter(candidateMask, letterIdx, confirmed, candidate, remaining, posMask);
            return;
        }

        // attempt binary split into lower-half and upper-half
        int left = takeLowerHalf(candidateMask);
        int right = candidateMask & ~left;

        // safety: if split didn't reduce the mask, fallback to single-bit probing
        if (left == 0 || left == candidateMask) {
            probeSingleBitsForLetter(letterIdx, need, candidateMask, fillerIndex, confirmed, candidate, remaining, posMask);
            return;
        }

        // query left side
        if (left != 0) {
            int cLeft = queryCountForMask(letterIdx, left, fillerIndex, confirmed, candidate);
            if (cLeft > 0) locatePositionsByBinarySplit(letterIdx, cLeft, left, fillerIndex, confirmed, candidate, remaining, posMask);
        }
        // query right side
        if (right != 0) {
            int cRight = queryCountForMask(letterIdx, right, fillerIndex, confirmed, candidate);
            if (cRight > 0) locatePositionsByBinarySplit(letterIdx, cRight, right, fillerIndex, confirmed, candidate, remaining, posMask);
        }
    }

    // probe single bits fallback: checks each candidate bit individually
    private void probeSingleBitsForLetter(int letterIdx, int need, int mask, int fillerIndex,
                                          boolean[] confirmed, char[] candidate, int[] remaining, int[] posMask) {
        int N = candidate.length;
        // how many confirmed positions already equal this letter?
        int baseConfirmedForLetter = 0;
        for (int i = 0; i < N; i++) if (confirmed[i] && candidate[i] == ALPH[letterIdx]) baseConfirmedForLetter++;

        for (int pos = 0; pos < N && need > 0; pos++) {
            int bit = 1 << pos;
            if ((mask & bit) == 0) continue;

            // build probe: letter at pos, filler at other unknowns
            char[] probe = new char[N];
            for (int i = 0; i < N; i++) {
                if (confirmed[i]) probe[i] = candidate[i];
                else if (i == pos) probe[i] = ALPH[letterIdx];
                else probe[i] = ALPH[fillerIndex];
            }

            int res = callGuess(new String(probe));
            log("SingleBitQuery '" + ALPH[letterIdx] + "' pos " + pos + " -> " + res);

            if (res > baseConfirmedForLetter) {
                // pos must be this letter
                confirmed[pos] = true;
                candidate[pos] = ALPH[letterIdx];
                need--;
                if (remaining[letterIdx] > 0) remaining[letterIdx]--;
                for (int k = 0; k < ALPH_SZ; k++) if (k != letterIdx) posMask[k] &= ~bit;
                baseConfirmedForLetter++;
                log("ProbeSingle: assigned pos " + pos + " -> '" + ALPH[letterIdx] + "'");
            }
        }
    }

    // build and run a probe that places letterIdx at all bits in mask and filler elsewhere
    private int queryCountForMask(int letterIdx, int mask, int fillerIndex, boolean[] confirmed, char[] candidate) {
        int N = candidate.length;
        char[] s = new char[N];
        for (int i = 0; i < N; i++) {
            if (confirmed[i]) s[i] = candidate[i];
            else if (((mask >> i) & 1) != 0) s[i] = ALPH[letterIdx];
            else s[i] = ALPH[fillerIndex];
        }
        int res = callGuess(new String(s));
        log("GroupQuery '" + ALPH[letterIdx] + "' mask " + maskToString(mask, N) + " -> " + res);
        return res;
    }

    // assign all positions in mask to the letter
    private void assignMaskToLetter(int mask, int letterIdx, boolean[] confirmed, char[] candidate,
                                    int[] remaining, int[] posMask) {
        int assigned = 0;
        for (int p = 0; p < candidate.length; p++) {
            int bit = 1 << p;
            if ((mask & bit) != 0 && !confirmed[p]) {
                confirmed[p] = true;
                candidate[p] = ALPH[letterIdx];
                assigned++;
                for (int k = 0; k < ALPH_SZ; k++) if (k != letterIdx) posMask[k] &= ~bit;
            }
        }
        remaining[letterIdx] -= assigned;
        if (remaining[letterIdx] < 0) remaining[letterIdx] = 0;
        log("Assigned mask " + maskToString(mask, candidate.length) + " => '" + ALPH[letterIdx] + "' (count " + assigned + ")");
    }

    // ---------------- single-position refinement ----------------
    private void singlePositionRefinement(boolean[] confirmed, char[] candidate,
                                          int[] remaining, int[] posMask, int baseline) {
        int N = candidate.length;
        int baselineMatches = baseline;

        // global priority: indices sorted by remaining descending
        int[] globalPriority = orderByCountsDesc(remaining);

        while (!allConfirmed(confirmed)) {
            // forced-fill optimization: if some letter must fill all open slots
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
                log("After forced-fill guess, baseline=" + baselineMatches);
                globalPriority = orderByCountsDesc(remaining);
                continue;
            }

            boolean progressed = false;

            // Try to confirm one position per outer loop iteration
            for (int pos = 0; pos < N && !progressed; pos++) {
                if (confirmed[pos]) continue;

                // Build try candidates using global priority and filters
                int[] tryCandidates = new int[ALPH_SZ];
                int t = 0;
                for (int gi = 0; gi < ALPH_SZ; gi++) {
                    int li = globalPriority[gi];
                    if (remaining[li] <= 0) continue;
                    if (((posMask[li] >> pos) & 1) == 0) continue;
                    if (ALPH[li] == candidate[pos]) continue; // skip current tentative char
                    tryCandidates[t++] = li;
                }
                if (t == 0) continue;

                for (int k = 0; k < t && !progressed; k++) {
                    int li = tryCandidates[k];
                    char test = ALPH[li];

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
                        log("Confirmed pos " + pos + " = '" + test + "' (+1). baseline=" + baselineMatches);
                        progressed = true;
                        globalPriority = orderByCountsDesc(remaining);
                        break;
                    } else if (delta == -1) {
                        // original candidate[pos] was correct
                        int origIdx = alphaIndex(candidate[pos]);
                        if (origIdx >= 0 && remaining[origIdx] > 0) remaining[origIdx]--;
                        confirmed[pos] = true;
                        int bit = 1 << pos;
                        for (int z = 0; z < ALPH_SZ; z++) if (z != origIdx) posMask[z] &= ~bit;
                        log("Confirmed pos " + pos + " = '" + candidate[pos] + "' (-1). baseline stays " + baselineMatches);
                        progressed = true;
                        globalPriority = orderByCountsDesc(remaining);
                        break;
                    } else {
                        // delta == 0: eliminate li at pos
                        posMask[li] &= ~(1 << pos);
                        log("Eliminated '" + test + "' at pos " + pos + " (0).");
                    }
                }
            } // end for pos

            if (!progressed) {
                log("No progress in single-position refinement. Breaking.");
                break;
            }
        } // end while
    }

    // ---------------- small helpers ----------------
    private int getUnconfirmedMask(boolean[] confirmed) {
        int mask = 0;
        for (int i = 0; i < confirmed.length; i++) if (!confirmed[i]) mask |= (1 << i);
        return mask;
    }

    private int findAbsentLetterIndex(int[] counts) {
        for (int i = 0; i < ALPH_SZ; i++) if (counts[i] == 0) return i;
        return -1;
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

    private String maskToString(int mask, int N) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < N; i++) sb.append(((mask >> i) & 1) != 0 ? "1" : "0");
        sb.append("]");
        return sb.toString();
    }

    private String repeatChar(char c, int n) {
        char[] a = new char[n];
        for (int i = 0; i < n; i++) a[i] = c;
        return new String(a);
    }

    private int takeLowerHalf(int mask) {
        int bits = Integer.bitCount(mask);
        if (bits <= 1) return mask;
        int need = bits / 2;
        int out = 0;
        for (int i = 0; i < 31 && need > 0; i++) {
            if (((mask >> i) & 1) != 0) { out |= (1 << i); need--; }
        }
        return out;
    }

    private int alphaIndex(char ch) {
        for (int i = 0; i < ALPH_SZ; i++) if (ALPH[i] == ch) return i;
        return -1;
    }

    // wrapper for harness.guess() that counts calls and prints guess lines
    private int callGuess(String s) {
        localGuessCount++;
        int res = harness.guess(s);
        if (LOG) System.out.println("GUESS#" + localGuessCount + " : \"" + s + "\" -> " + res);
        return res;
    }

    // simple log wrapper (allowed to disable easily)
    private void log(String s) { if (LOG) System.out.println(s); }

    // final report helper for early single-letter detection
    private void reportFinalSingleLetter(String secret) {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        System.out.println("Secret found (single-letter): " + secret);
        System.out.println("Total guesses: " + localGuessCount);
        System.out.println("Elapsed ms   : " + elapsed);
    }
}
