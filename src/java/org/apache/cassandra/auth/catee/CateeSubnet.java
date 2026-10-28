package org.apache.cassandra.auth.catee;

import java.nio.charset.StandardCharsets;

/** PATEE-style named IPv4 CIDR configuration, without DNS resolution. */
public final class CateeSubnet
{
    public final String name;
    public final long network;
    public final int prefix;

    public CateeSubnet(String name, String cidr)
    {
        encodeName(name);
        if (cidr == null) throw new IllegalArgumentException("IPv4 CIDR required");
        String[] parts = cidr.split("/", -1);
        if (parts.length != 2 || !parts[1].matches("[0-9]{1,2}"))
            throw new IllegalArgumentException("IPv4 CIDR required");
        prefix = Integer.parseInt(parts[1]);
        if (prefix > 32) throw new IllegalArgumentException("IPv4 prefix must be 0–32");
        String[] octets = parts[0].split("\\.", -1);
        if (octets.length != 4) throw new IllegalArgumentException("IPv4 address required");
        long address = 0;
        for (String octet : octets)
        {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255)
                throw new IllegalArgumentException("Invalid IPv4 octet");
            address = (address << 8) | Integer.parseInt(octet);
        }
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        if ((address & mask) != address) throw new IllegalArgumentException("CIDR has host bits set");
        this.name = name;
        network = address;
    }

    public static String encodeName(String name)
    {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0
            || !StandardCharsets.UTF_8.newEncoder().canEncode(name))
            throw new IllegalArgumentException("Valid subnet name required");
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 63) throw new IllegalArgumentException("Subnet name exceeds 63 UTF-8 bytes");
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes)
        {
            hex.append(Character.forDigit((value & 255) >>> 4, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }
}
