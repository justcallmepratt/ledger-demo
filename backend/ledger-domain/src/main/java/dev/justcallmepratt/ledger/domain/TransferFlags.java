package dev.justcallmepratt.ledger.domain;

public record TransferFlags(boolean linked,
                            boolean pending,
                            boolean postPendingTransfer,
                            boolean voidPendingTransfer) {

    public static final TransferFlags NONE = new TransferFlags(false, false, false, false);
    public static final TransferFlags PENDING = new TransferFlags(false, true, false, false);
    public static final TransferFlags POST = new TransferFlags(false, false, true, false);
    public static final TransferFlags VOID = new TransferFlags(false, false, false, true);

    public TransferFlags withLinked(boolean value) {
        return new TransferFlags(value, pending, postPendingTransfer, voidPendingTransfer);
    }

    /** True for post and void transfers, which resolve an earlier pending transfer. */
    public boolean resolves() {
        return postPendingTransfer || voidPendingTransfer;
    }
}
