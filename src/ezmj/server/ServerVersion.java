package ezmj.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 服務器版號。唯一來源是 version.properties，打包腳本也讀同一個檔案。 */
public final class ServerVersion {

    private static final String VERSION = load();

    private ServerVersion() {
    }

    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = ServerVersion.class.getResourceAsStream("version.properties")) {
            if (in == null) {
                return "dev";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }
}
