.bail on
PRAGMA foreign_keys = ON;
BEGIN;
INSERT INTO subscriptions (uid, service_id, url, name, subscriber_count, notification_mode) VALUES
    (1, 0, 'https://example.invalid/byte-workshop', 'Byte Workshop', 1200, 0),
    (2, 0, 'https://example.invalid/circuit-bench', 'Circuit Bench', 750, 0),
    (3, 0, 'https://example.invalid/studio-live', 'Studio Live', 980, 0);
INSERT INTO streams (uid, service_id, url, title, stream_type, duration, uploader, uploader_url, view_count, upload_date, is_paid) VALUES
    (1, 0, 'https://example.invalid/v1', 'Build a desk sensor with a microcontroller', 'VIDEO_STREAM', 771, 'Byte Workshop', 'https://example.invalid/byte-workshop', 1260, unixepoch('now','-2 hours') * 1000, 0),
    (2, 0, 'https://example.invalid/v2', 'What to check before soldering', 'VIDEO_STREAM', 613, 'Circuit Bench', 'https://example.invalid/circuit-bench', 365, unixepoch('now','-3 hours') * 1000, 0),
    (3, 0, 'https://example.invalid/v3', 'Studio Q&A: repairing old consoles', 'LIVE_STREAM', 0, 'Studio Live', 'https://example.invalid/studio-live', 41, NULL, 0),
    (4, 0, 'https://example.invalid/v4', 'A tour of the repair bench', 'VIDEO_STREAM', 502, 'Studio Live', 'https://example.invalid/studio-live', 780, unixepoch('now','-4 hours') * 1000, 0),
    (5, 0, 'https://example.invalid/v5', 'Short: three cable tips', 'VIDEO_STREAM', 35, 'Byte Workshop', 'https://example.invalid/byte-workshop', 570, unixepoch('now','-1 hour') * 1000, 0),
    (6, 0, 'https://example.invalid/v6', 'Live soldering session', 'LIVE_STREAM', 0, 'Circuit Bench', 'https://example.invalid/circuit-bench', 22, NULL, 0);
INSERT INTO feed (stream_id, subscription_id, content_selection) VALUES
    (1,1,1), (2,2,1), (3,3,4), (4,3,1), (5,1,2), (6,2,4);
INSERT INTO feed_last_updated (subscription_id, last_updated) SELECT uid, unixepoch('now') * 1000 FROM subscriptions;
COMMIT;
