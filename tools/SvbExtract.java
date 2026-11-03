import com.sabari.photovault.backup.BackupFormat;

import java.io.BufferedInputStream;
import java.io.Console;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opens a SecureVault backup (.svb) on a laptop/PC, without the phone app.
 *
 * Build + run on Ubuntu (from the project folder):
 *   javac -d /tmp/svb tools/SvbExtract.java app/src/main/java/com/sabari/photovault/backup/BackupFormat.java
 *   java -cp /tmp/svb SvbExtract ~/SecureVault-backup.svb ~/RestoredPhotos
 *
 * It asks for the backup password (typing is hidden) and saves the original photos into the folder.
 * WARNING: the extracted photos are NOT encrypted. Delete them when you are done.
 */
public class SvbExtract {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java -cp <classes> SvbExtract <backup.svb> <output-folder>");
            System.exit(1);
        }
        Path out = Paths.get(args[1]);
        Files.createDirectories(out);

        char[] password = readPassword();
        int count = 0;
        try (InputStream in = new BufferedInputStream(new FileInputStream(args[0]), 256 * 1024)) {
            BackupFormat.Reader reader = new BackupFormat.Reader(in, password);
            BackupFormat.Entry e;
            while ((e = reader.next()) != null) {
                count++;
                Path file = uniquePath(out, safeName(e.metaJson, count));
                Files.write(file, e.data);
                System.out.println("  saved " + file.getFileName());
            }
            System.out.println("Done: " + count + " photo(s) extracted to " + out.toAbsolutePath());
            System.out.println("Remember: these copies are NOT encrypted.");
        } catch (BackupFormat.WrongPasswordException ex) {
            System.err.println("Wrong password.");
            System.exit(2);
        } catch (BackupFormat.CorruptBackupException ex) {
            System.err.println("Problem with the file: " + ex.getMessage()
                    + " (" + count + " photo(s) were extracted before the problem)");
            System.exit(3);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static char[] readPassword() {
        String env = System.getenv("SVB_PASSWORD");     // only used for automated tests
        if (env != null) return env.toCharArray();
        Console c = System.console();
        if (c == null) {
            System.err.println("Run this in a terminal so the password can be typed safely.");
            System.exit(1);
        }
        return c.readPassword("Backup password: ");
    }

    /** Takes the original file name from the metadata, but only allows safe characters. */
    private static String safeName(String metaJson, int n) {
        Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(metaJson);
        String name = m.find() ? m.group(1) : "";
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("^\\.+", "");
        if (name.isBlank()) name = String.format("photo_%04d.jpg", n);
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static Path uniquePath(Path dir, String name) {
        Path p = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; Files.exists(p); i++) p = dir.resolve(base + "_" + i + ext);
        return p;
    }
}
