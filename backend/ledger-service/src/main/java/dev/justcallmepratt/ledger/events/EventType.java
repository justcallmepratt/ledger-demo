package dev.justcallmepratt.ledger.events;

/** Event kinds on the Kafka feed. CloudEvents type = prefix + name + ".v1". */
public enum EventType {
    TRANSFER_CREATED("transfer.created", Topics.TRANSFERS),
    TRANSFER_POSTED("transfer.posted", Topics.TRANSFERS),
    TRANSFER_VOIDED("transfer.voided", Topics.TRANSFERS),
    TRANSFER_EXPIRED("transfer.expired", Topics.TRANSFERS),
    ACCOUNT_CREATED("account.created", Topics.ACCOUNTS);

    public static final String TYPE_PREFIX = "dev.justcallmepratt.ledger.";
    public static final String TYPE_SUFFIX = ".v1";

    private final String shortName;
    private final String topic;

    EventType(String shortName, String topic) {
        this.shortName = shortName;
        this.topic = topic;
    }

    /** e.g. {@code transfer.created}, as shown in the activity feed. */
    public String shortName() {
        return shortName;
    }

    public String topic() {
        return topic;
    }

    public String cloudEventType() {
        return TYPE_PREFIX + shortName + TYPE_SUFFIX;
    }
}
