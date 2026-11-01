package ezmj.server;

/** 客戶端的要求不被接受，局面不變。回給客戶端 error 訊息，不會中斷連線或牌局。 */
final class Rejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    Rejected(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}
