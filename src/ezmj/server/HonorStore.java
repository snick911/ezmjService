package ezmj.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 榮譽榜存在哪裡。內容是一段文字，格式由 HonorBoard 決定。 */
interface HonorStore {

    /** 存過的內容，確定還沒存過才回傳 null。讀不到要丟出 IOException，當成沒有紀錄的話舊紀錄會被蓋掉。 */
    String read() throws IOException;

    void write(String content) throws IOException;

    /** 存成檔案，先寫到暫存檔再換過去。只給檔名也要找得到上層資料夾，所以先轉成絕對路徑。 */
    static HonorStore file(Path file) {
        Path path = file.toAbsolutePath();
        return new HonorStore() {
            @Override
            public String read() throws IOException {
                return Files.exists(path) ? Files.readString(path) : null;
            }

            @Override
            public void write(String content) throws IOException {
                Files.createDirectories(path.getParent());
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(temp, content);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        };
    }
}
