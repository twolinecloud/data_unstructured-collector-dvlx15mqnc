CREATE SCHEMA IF NOT EXISTS turaco_sample;
SET search_path TO turaco_sample;

CREATE TABLE IF NOT EXISTS sample (
   id VARCHAR(32) NOT NULL,
   "content" VARCHAR(255) NULL,
   "post" CHAR(1) NULL,
   CONSTRAINT sample_pkey PRIMARY KEY (id)
);