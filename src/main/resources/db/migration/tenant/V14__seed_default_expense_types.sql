SET @seed_expense_type_count = (SELECT COUNT(*) FROM expense_types);

INSERT INTO expense_types (active, count_in_profit_report, created_at, name)
SELECT b'1', b'1', NOW(6), default_names.name
FROM (
    SELECT 'Tea' AS name
    UNION ALL SELECT 'Lunch'
    UNION ALL SELECT 'Staff Meals'
    UNION ALL SELECT 'Transport'
    UNION ALL SELECT 'Fuel'
    UNION ALL SELECT 'Delivery Charges'
    UNION ALL SELECT 'Electricity Bill'
    UNION ALL SELECT 'Water Bill'
    UNION ALL SELECT 'Internet Bill'
    UNION ALL SELECT 'Telephone Bill'
    UNION ALL SELECT 'Rent'
    UNION ALL SELECT 'Stationery'
    UNION ALL SELECT 'Packaging Materials'
    UNION ALL SELECT 'Cleaning Supplies'
    UNION ALL SELECT 'Shop Maintenance'
    UNION ALL SELECT 'Equipment Repairs'
    UNION ALL SELECT 'Salary Advance'
    UNION ALL SELECT 'Staff Welfare'
    UNION ALL SELECT 'Marketing And Ads'
    UNION ALL SELECT 'Donations'
    UNION ALL SELECT 'Miscellaneous'
    UNION ALL SELECT 'Other'
) default_names
WHERE @seed_expense_type_count = 0;
