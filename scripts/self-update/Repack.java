import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A server jar with another Implementation-Version, for the self-update test in
 * the verify scripts (#584). TEST ONLY.
 *
 *   java Repack.java app.jar test.jar 0.9.9.7.1
 *
 * Prints the version the input carried. Every entry is copied as it was -
 * stored entries stay stored, which Spring Boot needs of the libraries it reads
 * in place - and any signature is dropped, since it is about to be signed again.
 */
public class Repack {
    public static void main(String[] args) throws Exception {
        try (ZipFile in = new ZipFile(args[0]);
             ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[1]))) {
            Manifest manifest;
            try (InputStream stream = in.getInputStream(in.getEntry("META-INF/MANIFEST.MF"))) {
                manifest = new Manifest(stream);
            }
            Attributes main = manifest.getMainAttributes();
            System.out.println(main.getValue(Attributes.Name.IMPLEMENTATION_VERSION));
            main.put(Attributes.Name.IMPLEMENTATION_VERSION, args[2]);
            manifest.getEntries().clear();
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            manifest.write(out);
            out.closeEntry();

            Enumeration<? extends ZipEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                String upper = name.toUpperCase();
                if (upper.equals("META-INF/MANIFEST.MF")) continue;
                if (upper.startsWith("META-INF/") && upper.indexOf('/', 9) < 0
                        && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".EC") || upper.endsWith(".DSA"))) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                if (entry.getMethod() == ZipEntry.STORED) {
                    copy.setMethod(ZipEntry.STORED);
                    copy.setSize(entry.getSize());
                    copy.setCompressedSize(entry.getSize());
                    copy.setCrc(entry.getCrc());
                }
                out.putNextEntry(copy);
                try (InputStream stream = in.getInputStream(entry)) {
                    stream.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }
}
