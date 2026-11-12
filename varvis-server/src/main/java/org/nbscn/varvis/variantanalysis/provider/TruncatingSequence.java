package org.nbscn.varvis.variantanalysis.provider;

/** Standard-code sequence mechanics shared by versioned PVS1 runtimes. No disease policy. */
final class TruncatingSequence {
    private TruncatingSequence() { }

    static char translateCodon(String codon) {
        String bases = "TCAG";
        return "FFLLSSSSYY**CC*WLLLLPPPPHHQQRRRRIIIMTTTTNNKKSSRRVVVVAAAADDEEGGGG".charAt(
                bases.indexOf(codon.charAt(0)) * 16 + bases.indexOf(codon.charAt(1)) * 4 + bases.indexOf(codon.charAt(2)));
    }

    static String aminoName(char code) {
        String letters = "ACDEFGHIKLMNPQRSTVWY";
        String[] names = {"Ala","Cys","Asp","Glu","Phe","Gly","His","Ile","Lys","Leu","Met","Asn","Pro","Gln","Arg","Ser","Thr","Val","Trp","Tyr"};
        int index = letters.indexOf(code);
        return index < 0 ? "Ter" : names[index];
    }

    static String reverseComplement(String sequence) {
        StringBuilder result = new StringBuilder(sequence.length());
        for (int i = sequence.length() - 1; i >= 0; i--) {
            result.append(switch (sequence.charAt(i)) {
                case 'A' -> 'T'; case 'C' -> 'G'; case 'G' -> 'C'; case 'T' -> 'A';
                default -> throw new IllegalArgumentException("Non-ACGT coding allele");
            });
        }
        return result.toString();
    }
}
