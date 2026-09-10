package com.fdmultimedia.worker;

final class ImportFailureException extends Exception {

    private final String code;
    private final boolean terminal;

    ImportFailureException(String code, String message, boolean terminal) {
        super(message);
        this.code = code;
        this.terminal = terminal;
    }

    String code() {
        return code;
    }

    boolean terminal() {
        return terminal;
    }
}
