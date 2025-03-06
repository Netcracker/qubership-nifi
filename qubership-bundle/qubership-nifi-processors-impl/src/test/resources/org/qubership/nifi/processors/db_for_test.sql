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

SET TIME ZONE 'UTC';

create table IDB_TEST_TABLE
(
    SOURCE_ID VARCHAR(255) not null constraint PK_TEST_TABLE primary key,
    CODE VARCHAR(255) not null,
    VAL VARCHAR(255) not null
);

create table IDB_TEST_TABLE_2
(
    SOURCE_ID VARCHAR(255) not null constraint PK_TEST_TABLE_2 primary key,
    VAL1 VARCHAR(255) not null,
    VAL2 VARCHAR(255) not null
);


INSERT INTO IDB_TEST_TABLE (SOURCE_ID, CODE, VAL)
VALUES ('TEST_ID#000001', 'TEST-CODE-0001', 'VAL1'),
       ('TEST_ID#000002', 'TEST-CODE-0001', 'VAL2'),
       ('TEST_ID#000003', 'TEST-CODE-0001', 'VAL3'),
       ('TEST_ID#000004', 'TEST-CODE-0002', 'VAL4'),
       ('TEST_ID#000005', 'TEST-CODE-0002', 'VAL5'),
       ('TEST_ID#000006', 'TEST-CODE-0002', 'VAL6');

INSERT INTO IDB_TEST_TABLE_2 (SOURCE_ID, VAL1, VAL2)
VALUES ('TEST_ID#000001', 'VAL1', 'VAL11'),
       ('TEST_ID#000002', 'VAL2', 'VAL22'),
       ('TEST_ID#000003', 'VAL3', 'VAL33'),
       ('TEST_ID#000004', 'VAL4', 'VAL44'),
       ('TEST_ID#000005', 'VAL5', 'VAL55'),
       ('TEST_ID#000006', 'VAL6', 'VAL66');

commit;
