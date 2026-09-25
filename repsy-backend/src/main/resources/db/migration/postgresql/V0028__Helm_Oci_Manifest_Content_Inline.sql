-- RPS-1392: helm_oci_manifest.content holds the manifest JSON itself.
--
-- The entity mapped the column with @Lob. For a String that makes Hibernate bind a CLOB, and
-- PostgreSQL then stores the JSON as a large object (pg_largeobject) and only its OID, as text
-- ("17509"), in the text column: SQL could not read the manifest, and deleting the row left the
-- large object behind. The mapping is now a plain text column (no @Lob), which Hibernate reads and
-- writes as the string; this migration rewrites the rows the old mapping wrote.
--
-- A row is one of the old kind when its content is nothing but the OID of an existing large
-- object. A manifest is a JSON object, so a row that is already the JSON (a database on which the
-- column was written as text, or a re-run) never matches. The checks are a CASE because SQL does
-- not promise the order of AND operands: an OID cast of the JSON of another row must never run.
-- The large object of each converted row is unlinked with it (both in this transaction), so the
-- rewrite leaves no orphan. Large objects of rows deleted before this migration are not tracked
-- anywhere and are not touched; `vacuumlo` removes them.
with legacy as (
    select "id", "content"::oid as "lo"
    from "public"."helm_oci_manifest"
    where case
              when "content" !~ '^[0-9]{1,10}$' then false
              when "content"::bigint > 4294967295 then false
              else exists (select 1 from pg_largeobject_metadata where "oid" = "content"::oid)
              end
),
converted as (
    update "public"."helm_oci_manifest" m
        set "content" = convert_from(lo_get(legacy."lo"), 'UTF8')
        from legacy
        where m."id" = legacy."id"
        returning legacy."lo"
)
select lo_unlink("lo")
from converted;
