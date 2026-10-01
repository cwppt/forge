/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import java.security.SecureRandom;
import java.util.Random;
import java.util.function.Supplier;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    /** Constant <code>random</code>. */
    private static Random random = new SecureRandom();
    private static final ThreadLocal<Random> localRandom = new ThreadLocal<>();

    /**
     * <p>
     * percentTrue.<br>
     * If percent is like 30, then 30% of the time it will be true.
     * </p>
     * 
     * @param percent an int.
     * @return a boolean.
     */
    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * Gets the random.
     * 
     * @return the random
     */
    public static Random getRandom() {
        Random local = localRandom.get();
        return local == null ? MyRandom.random : local;
    }

    /**
     * Sets the random provider. Used for deterministic simulation.
     * @param random the random
     */
    public static void setRandom(Random random) {
        if (localRandom.get() == null) {
            MyRandom.random = random;
        } else {
            localRandom.set(random);
        }
    }

    /**
     * Runs isolated work with a thread-local random provider. The process-wide random stream is neither replaced
     * nor advanced. This is intended for copied-game evaluation, not live game play.
     */
    public static <T> T withRandom(Random random, Supplier<T> work) {
        Random previous = localRandom.get();
        localRandom.set(random);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                localRandom.remove();
            } else {
                localRandom.set(previous);
            }
        }
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];

        for (int i = 0; i < value; i++) {
            groups[getRandom().nextInt(numGroups)]++;
        }

        return groups;
    }
}
