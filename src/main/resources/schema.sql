CREATE TABLE IF NOT EXISTS charges (
    id UUID PRIMARY KEY,
    description VARCHAR(100) NOT NULL,
    amount_cents BIGINT NOT NULL CHECK (amount_cents BETWEEN 1 AND 99999999),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_attempt_id UUID
);

CREATE TABLE IF NOT EXISTS payment_attempts (
    id UUID NOT NULL,
    charge_id UUID NOT NULL REFERENCES charges(id),
    method VARCHAR(12) NOT NULL CHECK (method IN ('PIX', 'CREDIT', 'DEBIT')),
    scenario VARCHAR(12) NOT NULL CHECK (scenario IN ('APPROVE', 'DECLINE')),
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'DECLINED', 'SUPERSEDED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (charge_id, id)
);

CREATE INDEX IF NOT EXISTS payment_attempts_charge_idx ON payment_attempts (charge_id, created_at);
