-- 4c runtime signal provider: optional per-symbol runtime features (test coverage, call frequency)
-- pushed out-of-band from a coverage/trace export. weight = callFrequency * (1 - coverage), so a
-- hot-and-untested symbol ranks highest. Absence changes nothing: impact_of behaves as before.
CREATE TABLE IF NOT EXISTS symbol_signal (
    repo       TEXT             NOT NULL,
    symbol_id  TEXT             NOT NULL,
    coverage   DOUBLE PRECISION NOT NULL DEFAULT 0,
    call_freq  DOUBLE PRECISION NOT NULL DEFAULT 0,
    weight     DOUBLE PRECISION NOT NULL DEFAULT 0,
    PRIMARY KEY (repo, symbol_id)
);
