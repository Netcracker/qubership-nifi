-- Copyright 2020-2025 NetCracker Technology Corporation
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

set time zone 'UTC';

create table IDB_TEST_TABLE
(
    SOURCE_ID varchar(255) not null constraint PK_TEST_TABLE primary key,
    CODE varchar(255) not null,
    VAL varchar(255) not null
);

create table IDB_TEST_TABLE_2
(
    SOURCE_ID varchar(255) not null constraint PK_TEST_TABLE_2 primary key,
    VAL1 varchar(255) not null,
    VAL2 varchar(255) not null
);


insert into IDB_TEST_TABLE (SOURCE_ID, CODE, VAL)
values ('TEST_ID#000001', 'TEST-CODE-0001', 'VAL1'),
('TEST_ID#000002', 'TEST-CODE-0001', 'VAL2'),
('TEST_ID#000003', 'TEST-CODE-0001', 'VAL3'),
('TEST_ID#000004', 'TEST-CODE-0002', 'VAL4'),
('TEST_ID#000005', 'TEST-CODE-0002', 'VAL5'),
('TEST_ID#000006', 'TEST-CODE-0002', 'VAL6');

insert into IDB_TEST_TABLE_2 (SOURCE_ID, VAL1, VAL2)
values ('TEST_ID#000001', 'VAL1', 'VAL11'),
('TEST_ID#000002', 'VAL2', 'VAL22'),
('TEST_ID#000003', 'VAL3', 'VAL33'),
('TEST_ID#000004', 'VAL4', 'VAL44'),
('TEST_ID#000005', 'VAL5', 'VAL55'),
('TEST_ID#000006', 'VAL6', 'VAL66');

commit;
