-- A card sale / recharge can be paid with several modes (e.g. part UPI, part cash).
-- It stays ONE ledger entry; this column holds how it was paid: [{"mode":"upi","amount_paise":60000}, ...].
-- payment_mode keeps the single mode, or "split" when more than one was used. Older rows have NULL here
-- and are read from payment_mode as before.
ALTER TABLE membership_ledger ADD COLUMN payment_breakdown JSON;
