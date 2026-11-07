package fr.claudegateway.jarvis.clientmail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.hpsf.ClassID;
import org.apache.poi.hsmf.datatypes.NameIdChunks;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/**
 * Fabrique de {@code .msg} pour les tests (F-249 / SF-249-01) — un fichier Outlook (OLE2, [MS-OXMSG]) écrit par POIFS :
 * chaînes en {@code __substg1.0_XXXX001F} (UTF-16LE), binaires en {@code …0102}, dates dans
 * {@code __properties_version1.0} (FILETIME), propriétés nommées dans {@code __nameid_version1.0}, destinataires et
 * pièces jointes dans leurs sous-stockages.
 */
final class MsgFixture {

    private record Named(ClassID guid, long lid, int type, Object value) {
    }

    private final Map<Integer, String> strings = new LinkedHashMap<>();
    private final Map<Integer, byte[]> binaries = new LinkedHashMap<>();
    private final Map<Integer, Instant> times = new LinkedHashMap<>();
    private final List<String[]> recipients = new ArrayList<>();
    private final List<Object[]> attachments = new ArrayList<>();
    private final List<Named> named = new ArrayList<>();

    MsgFixture string(int id, String value) {
        strings.put(id, value);
        return this;
    }

    MsgFixture time(int id, Instant value) {
        times.put(id, value);
        return this;
    }

    MsgFixture recipient(String smtp, String name, int type) {
        recipients.add(new String[] {smtp, name, String.valueOf(type)});
        return this;
    }

    MsgFixture attachment(String fileName, String mime, byte[] data) {
        attachments.add(new Object[] {fileName, mime, data});
        return this;
    }

    MsgFixture namedTime(NameIdChunks.PredefinedPropertySet set, long lid, Instant value) {
        named.add(new Named(set.getClassID(), lid, 0x0040, value));
        return this;
    }

    MsgFixture namedString(NameIdChunks.PredefinedPropertySet set, long lid, String value) {
        named.add(new Named(set.getClassID(), lid, 0x001F, value));
        return this;
    }

    MsgFixture namedBinary(NameIdChunks.PredefinedPropertySet set, long lid, byte[] value) {
        named.add(new Named(set.getClassID(), lid, 0x0102, value));
        return this;
    }

    byte[] bytes() throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            DirectoryEntry root = fs.getRoot();
            Map<Integer, Instant> allTimes = new LinkedHashMap<>(times);
            Map<Integer, String> allStrings = new LinkedHashMap<>(strings);
            Map<Integer, byte[]> allBinaries = new LinkedHashMap<>(binaries);
            if (!named.isEmpty()) {
                List<ClassID> guids = new ArrayList<>();
                ByteBuffer entries = le(named.size() * 8);
                for (int i = 0; i < named.size(); i++) {
                    Named n = named.get(i);
                    int g = guids.indexOf(n.guid());
                    if (g < 0) {
                        guids.add(n.guid());
                        g = guids.size() - 1;
                    }
                    entries.putInt((int) n.lid());
                    entries.putShort((short) (((g + 3) << 1)));
                    entries.putShort((short) i);
                    int id = 0x8000 + i;
                    switch (n.type()) {
                        case 0x0040 -> allTimes.put(id, (Instant) n.value());
                        case 0x001F -> allStrings.put(id, (String) n.value());
                        default -> allBinaries.put(id, (byte[]) n.value());
                    }
                }
                ByteBuffer guidStream = le(guids.size() * 16);
                for (ClassID g : guids) {
                    byte[] b = new byte[16];
                    g.write(b, 0);
                    guidStream.put(b);
                }
                DirectoryEntry nameid = root.createDirectory("__nameid_version1.0");
                nameid.createDocument("__substg1.0_00020102", new ByteArrayInputStream(guidStream.array()));
                nameid.createDocument("__substg1.0_00030102", new ByteArrayInputStream(entries.array()));
                nameid.createDocument("__substg1.0_00040102", new ByteArrayInputStream(new byte[0]));
            }
            allStrings.forEach((id, v) -> doc(root, String.format("__substg1.0_%04X001F", id), utf16(v)));
            allBinaries.forEach((id, v) -> doc(root, String.format("__substg1.0_%04X0102", id), v));
            ByteArrayOutputStream props = new ByteArrayOutputStream();
            props.write(new byte[8]);
            props.write(le(16).putInt(recipients.size()).putInt(attachments.size()).putInt(recipients.size())
                    .putInt(attachments.size()).array());
            props.write(new byte[8]);
            for (Map.Entry<Integer, Instant> e : allTimes.entrySet()) {
                props.write(le(16).putInt((e.getKey() << 16) | 0x0040).putInt(6).putLong(filetime(e.getValue()))
                        .array());
            }
            for (Map.Entry<Integer, String> e : allStrings.entrySet()) {
                props.write(le(16).putInt((e.getKey() << 16) | 0x001F).putInt(6)
                        .putInt(utf16(e.getValue()).length + 2).putInt(0).array());
            }
            root.createDocument("__properties_version1.0", new ByteArrayInputStream(props.toByteArray()));
            for (int i = 0; i < recipients.size(); i++) {
                String[] r = recipients.get(i);
                DirectoryEntry d = root.createDirectory(String.format("__recip_version1.0_#%08X", i));
                doc(d, "__substg1.0_39FE001F", utf16(r[0]));
                doc(d, "__substg1.0_3001001F", utf16(r[1]));
                ByteArrayOutputStream rp = new ByteArrayOutputStream();
                rp.write(new byte[8]);
                rp.write(le(16).putInt((0x0C15 << 16) | 0x0003).putInt(6).putInt(Integer.parseInt(r[2])).putInt(0)
                        .array());
                d.createDocument("__properties_version1.0", new ByteArrayInputStream(rp.toByteArray()));
            }
            for (int i = 0; i < attachments.size(); i++) {
                Object[] a = attachments.get(i);
                DirectoryEntry d = root.createDirectory(String.format("__attach_version1.0_#%08X", i));
                doc(d, "__substg1.0_3707001F", utf16((String) a[0]));
                doc(d, "__substg1.0_370E001F", utf16((String) a[1]));
                doc(d, "__substg1.0_37010102", (byte[]) a[2]);
                d.createDocument("__properties_version1.0", new ByteArrayInputStream(new byte[8]));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            fs.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static void doc(DirectoryEntry dir, String name, byte[] bytes) {
        try {
            dir.createDocument(name, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] utf16(String s) {
        return s.getBytes(StandardCharsets.UTF_16LE);
    }

    /** FILETIME : centaines de nanosecondes depuis le 1er janvier 1601. */
    private static long filetime(Instant t) {
        return (t.getEpochSecond() + 11_644_473_600L) * 10_000_000L + t.getNano() / 100;
    }
}
