/*
 * JEmuleServer - An experimental eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (herniatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class Obfuscation {
    public static class RC4 {
        private final byte[] s = new byte[256];
        private int i = 0;
        private int j = 0;

        public RC4(byte[] key) {
            for (int k = 0; k < 256; k++) s[k] = (byte) k;
            int j2 = 0;
            for (int i2 = 0; i2 < 256; i2++) {
                j2 = (j2 + (s[i2] & 0xFF) + (key[i2 % key.length] & 0xFF)) & 0xFF;
                byte temp = s[i2];
                s[i2] = s[j2];
                s[j2] = temp;
            }
        }

        public void crypt(byte[] data) {
            crypt(data, 0, data.length);
        }

        public void crypt(byte[] data, int offset, int len) {
            for (int k = 0; k < len; k++) {
                i = (i + 1) & 0xFF;
                j = (j + (s[i] & 0xFF)) & 0xFF;
                byte temp = s[i];
                s[i] = s[j];
                s[j] = temp;
                int t = ((s[i] & 0xFF) + (s[j] & 0xFF)) & 0xFF;
                data[offset + k] ^= s[t];
            }
        }
    }

    public static byte[] md5(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
