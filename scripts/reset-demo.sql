-- Reset the demo data to the seed state without restarting the app.
-- signing_key is kept: the running app's in-memory key is registered there,
-- and dropping it would break presentment until the next boot.
-- TRUNCATE bypasses audit_event's row-level append-only trigger by design;
-- this is a demo reset, never something to run against real data.
set search_path = cts;
truncate cheque_image, ledger_entry, audit_event, cheque, settlement_batch,
         positive_pay, stop_payment, drawee_account, deposit_account,
         app_user, bank, return_reason
         restart identity cascade;

insert into bank (code, name, is_presenting) values
    ('999', 'CTS Demo Bank', true),
    ('002', 'State Bank of India', false),
    ('240', 'HDFC Bank', false),
    ('229', 'ICICI Bank', false),
    ('211', 'Axis Bank', false);

-- All demo users share the password Cts@2026.
insert into app_user (username, password_hash, full_name, roles) values
    ('maker',   '$2a$10$IEELaezMvYg4bJ4gcm1vQ.PBJX2vELsOAHymW49fl3v7vUFlNHsTK', 'Priya Maker',    'MAKER'),
    ('checker', '$2a$10$IEELaezMvYg4bJ4gcm1vQ.PBJX2vELsOAHymW49fl3v7vUFlNHsTK', 'Rahul Checker',  'CHECKER'),
    ('ops',     '$2a$10$IEELaezMvYg4bJ4gcm1vQ.PBJX2vELsOAHymW49fl3v7vUFlNHsTK', 'Anita Ops',      'OPS'),
    ('drawee',  '$2a$10$IEELaezMvYg4bJ4gcm1vQ.PBJX2vELsOAHymW49fl3v7vUFlNHsTK', 'Vikram Drawee',  'DRAWEE'),
    ('admin',   '$2a$10$IEELaezMvYg4bJ4gcm1vQ.PBJX2vELsOAHymW49fl3v7vUFlNHsTK', 'Demo Admin',     'MAKER,CHECKER,OPS,DRAWEE');

-- Common return reasons. 88 carries the specific reason in status_reason
-- where the full NPCI list has its own code (account closed, PPS mismatch).
insert into return_reason (code, description) values
    ('01', 'Funds insufficient'),
    ('02', 'Exceeds arrangement'),
    ('03', 'Effects not cleared, present again'),
    ('04', 'Refer to drawer'),
    ('10', 'Drawer''s signature incomplete'),
    ('12', 'Drawer''s signature differs'),
    ('20', 'Payment stopped by drawer'),
    ('21', 'Payment stopped by attachment order'),
    ('82', 'Bank / branch blocked'),
    ('83', 'Digital certificate validation failure'),
    ('85', 'Alterations other than date'),
    ('86', 'Fake / forged / stolen'),
    ('88', 'Other reasons'),
    ('92', 'Bank excluded');

insert into deposit_account (account_no, holder_name, balance_paise) values
    ('999000100001', 'Meera Textiles Pvt Ltd', 0),
    ('999000100002', 'Arjun Sharma', 0),
    ('999000100003', 'Kaveri Traders', 0);

insert into drawee_account (micr_code, account_no, holder_name, balance_paise, status) values
    ('400002101', '100201', 'Rohan Mehta',         50000000, 'ACTIVE'),  -- SBI Mumbai, ₹5,00,000
    ('110240102', '240511', 'Sunita Kapoor',       30000000, 'ACTIVE'),  -- HDFC Delhi, ₹3,00,000
    ('560229103', '229812', 'Nandini Rao',          1500000, 'ACTIVE'),  -- ICICI Bengaluru, ₹15,000
    ('600211104', '211044', 'Karthik Iyer',        90000000, 'ACTIVE'),  -- Axis Chennai, ₹9,00,000
    ('400002105', '100777', 'Old Account Holder',   1000000, 'CLOSED');  -- SBI Mumbai, closed

-- Rohan stopped cheque 000045.
insert into stop_payment (account_id, serial)
select id, '000045' from drawee_account where account_no = '100201';

-- Karthik registered cheque 000310 for ₹6,00,000 to Meera Textiles.
insert into positive_pay (account_id, serial, cheque_date, payee_name, amount_paise)
select id, '000310', current_date, 'Meera Textiles Pvt Ltd', 60000000
from drawee_account where account_no = '211044';
