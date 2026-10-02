-- Table name is quoted throughout as a defensive habit: several plausible domain
-- names (e.g. "user", "group", "order") are reserved PostgreSQL keywords.
CREATE TABLE "catalog" (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    price_cents INT NOT NULL,
    sku TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
