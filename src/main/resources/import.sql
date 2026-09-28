insert into medications(id, reserved, stock, name)
values (1, 0, 5, 'Amoxicillin (Antibiotic)'),
       (2, 0, 10, 'Ibuprofen (Pain Relief)'),
       (3, 0, 15, 'Lisinopril (Blood Pressure)'),
       (4, 0, 20, 'Metformin (Diabetes)'),
       (5, 0, 25, 'Atorvastatin (Cholesterol)');
alter sequence medications_seq restart with 6;