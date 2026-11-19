-- I-003 C+A: type-aware overload resolution.
-- dst_param_types: inferred call-site arg types (CSV of simple type names) when every argument is
--   syntactically obvious (literal / new Type(...) / Type.class); null otherwise. Lets the resolver
--   pick the exact overload among same-arity candidates (tier A).
-- ambiguous: set when a same-arity tie could NOT be split, so the edge was fanned out to all tied
--   candidates (tier C) — a best-guess, not a misattributed-as-missing caller.
ALTER TABLE edges ADD COLUMN IF NOT EXISTS dst_param_types TEXT;
ALTER TABLE edges ADD COLUMN IF NOT EXISTS ambiguous BOOLEAN NOT NULL DEFAULT false;
