-- V2__add_loan_id_to_loan_requests.sql
-- Stores the loans-service loan ID after confirmation.
-- Nullable: populated only when the saga reaches CONFIRMED status.
ALTER TABLE loan_requests ADD COLUMN loan_id UUID;
