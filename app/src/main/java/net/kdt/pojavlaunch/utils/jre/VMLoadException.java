package net.kdt.pojavlaunch.utils.jre;

/**
 * Compatibility exception constructed by the pinned MojoLauncher native JVM
 * loader when an Android runtime stage fails.
 */
public final class VMLoadException extends Exception {
    private final int loadStep;
    private final int errorCode;

    public VMLoadException(String errorInfo, int loadStep, int errorCode) {
        super(errorInfo);
        this.loadStep = loadStep;
        this.errorCode = errorCode;
    }

    public int getLoadStep() {
        return loadStep;
    }

    public int getErrorCode() {
        return errorCode;
    }

    @Override
    public String getMessage() {
        return "Embedded JVM load stage "
            + loadStep
            + " failed with code "
            + errorCode
            + ": "
            + super.getMessage();
    }
}
