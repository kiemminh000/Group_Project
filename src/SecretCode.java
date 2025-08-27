public class SecretCode {
    private String correctCode;
    private long counter;

    public SecretCode() {
        // for the real test, your program will not know this
        correctCode = "BACXIUBACXIUBA";
        counter = 0;
    }
    // Returns
    // -2 : if length of guessedCode is wrong
    // -1 : if guessedCode contains invalid characters
    // >=0 : number of correct characters in correct positions
    public int guess(String guessedCode) {
        counter++;
        // validation
        for (int i = 0; i < guessedCode.length(); i++) {
            char c = guessedCode.charAt(i);
            if (c != 'B' && c != 'A' && c != 'C' && c != 'X' && c != 'I' && c != 'U') {
                return -1;
            }
        }

        if (guessedCode.length() != correctCode.length()) {
            return -2;
        }

        int matched = 0;
        for(int i=0; i < correctCode.length(); i++){
            if(guessedCode.charAt(i) == correctCode.charAt(i)){
                matched++;
            }
        }

        if (matched == correctCode.length()) {
            System.out.println("Number of guesses: " + counter);
        }
        return matched;
    }

    public static void main(String[] args) {
        long t1 = System.currentTimeMillis();
        new SecretCodeGuesser().start();
        long t2 = System.currentTimeMillis();
        System.out.println("Time taken: " + (t2-t1) + " ms");
    }
}
