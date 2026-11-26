package dev.justcallmepratt.ledger.events;

/** CloudEvents 1.0 envelope, structured JSON mode. {@code time} is ISO-8601 UTC. */
public record CloudEvent(String specversion, String id, String source, String type, String time, String subject,
                         String datacontenttype, Object data) {
}
