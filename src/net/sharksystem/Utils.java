package net.sharksystem;

import net.sharksystem.asap.ASAP;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

public class Utils {
    public static String url2FileName(String url) {
        // escape:
        /*
        see https://en.wikipedia.org/wiki/Percent-encoding
        \ - %5C, / - %2F, : - %3A, ? - %3F," - %22,< - %3C,> - %3E,| - %7C
        */

        if(url == null) return null; // to be safe

        String newString = url.replace("\\", "%5C");
        newString = newString.replace("/", "%2F");
        newString = newString.replace(":", "%3A");
        newString = newString.replace("?", "%3F");
        newString = newString.replace("\"", "%22");
        newString = newString.replace("<", "%3C");
        newString = newString.replace(">", "%3E");
        newString = newString.replace("|", "%7C");

        return newString;
    }

    /**
     *
     * @param rootFolder
     * @return collection of integer values depicting era present in that folder
     */
    public static Collection<Integer> getErasInFolder(String rootFolder) {
        Collection<Integer> eras = new HashSet<>();
        File dir = new File(rootFolder);
        String[] dirEntries = dir.list();
        if (dirEntries != null) {
            for (String fileName : dirEntries) {
                // era folder?
                try {
                    int era = Integer.parseInt(fileName);
                    // It is an era folder
                    eras.add(era);
                } catch (NumberFormatException e) {
                    // no number - no problem - go ahead!
                }
            }
        }
        return eras;
    }

    /**
     *
     * @param searchSpace list of possible eras
     * @param fromEra lowest era
     * @param toEra highest era
     * @return list of era which are within from and to and also in search space
     */
    public static Collection<Integer> getErasInRange(Collection<Integer> searchSpace,
                                                     int fromEra, int toEra) {

        Collection<Integer> eras = new ArrayList<>();

        // the only trick is to be aware of the cyclic nature of era numbers
        boolean wrapped = fromEra > toEra; // it reached the era end and started new

        for(Integer era : searchSpace) {
            if(!wrapped) {
                //INIT ---- from-> +++++++++++++ <-to ----- MAX (+ fits)
                if(era >= fromEra && era <= toEra) eras.add(era);
            } else {
                // INIT+++++++++<-to ------ from->++++++MAX
                if(era <= toEra && era >= ASAP.INITIAL_ERA
                    || era >= fromEra && era <= ASAP.MAX_ERA
                ) eras.add(era);
            }
        }

        return eras;

    }

}
