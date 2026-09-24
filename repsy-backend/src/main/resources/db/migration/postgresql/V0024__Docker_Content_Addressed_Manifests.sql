-- RPS-1216: Docker manifests become content-addressed.
--
-- Until now a manifest was a child of a tag (docker_manifest.tag_platform_id -> docker_tag_platform
-- -> docker_tag): overriding a tag rewrote the tag's one manifest row and file in place, so the
-- previous manifest could no longer be pulled by its digest, and two tags with one digest shared a
-- row that moved between them. From now on:
--
--   docker_manifest        one row per (image, sha256 digest), whatever tags point at it
--   docker_tag             a movable pointer to a docker_manifest (manifest_id)
--   docker_manifest_child  the manifests an index (manifest list) references
--
-- A manifest file is stored as manifests/<sha256 digest> (like blobs/<digest>). Files written by the
-- previous versions live under a name generated from the tag they were pushed under; the row keeps
-- that reference in storage_name until DockerManifestLayoutRepairService renames the file and clears
-- the column, and the registry reads through storage_name in the meantime. digest_sha512 cannot be
-- computed in SQL (only a manifest that had a 'sha512:<hex>' tag keeps its digest from the tag): the
-- same service fills it from the stored bytes (a later migration makes it NOT NULL once every
-- install has run it).
--
-- The legacy docker_tag_platform table and docker_manifest.tag_platform_id / name are left in place
-- and unmapped (a later migration drops them). Flyway migrations are forward-only: back up the
-- database and the storage before upgrading.

-- The old foreign key would delete a manifest together with the tag platform of a deleted tag, which
-- is exactly what a tag is no longer allowed to do. It has to go before any tag row is removed.
ALTER TABLE "docker_manifest" DROP CONSTRAINT "fk_docker_manifest__tag_platform_id";

ALTER TABLE "docker_manifest" ADD COLUMN "image_id"      uuid;
ALTER TABLE "docker_manifest" ADD COLUMN "digest_sha512" varchar(255);
ALTER TABLE "docker_manifest" ADD COLUMN "storage_name"  varchar(255);
ALTER TABLE "docker_manifest" ALTER COLUMN "name" DROP NOT NULL;
ALTER TABLE "docker_tag"      ADD COLUMN "manifest_id"   uuid;

-- The image of a manifest was implied by its tag; storage_name is the reference its file name was
-- generated from.
UPDATE "docker_manifest"
SET "image_id"     = (SELECT t."image_id"
                      FROM "docker_tag_platform" tp
                               JOIN "docker_tag" t ON t."id" = tp."tag_id"
                      WHERE tp."id" = "docker_manifest"."tag_platform_id"),
    "storage_name" = "name";

-- Rows that belong to no tag (leftovers of pushes that failed half way) have no image and cannot be
-- reached by any request. Their layer links go with them.
DELETE FROM "docker_manifest" WHERE "image_id" IS NULL;

-- One row per (image, digest): the multi-platform tracking rows (name = the child's digest, added
-- next to the child's own row so a tag could list it) and the copies a re-push under a second tag
-- made are folded into the row of the original push, the oldest one.
CREATE TABLE "docker_manifest_dedupe"
(
    "id"          uuid PRIMARY KEY,
    "survivor_id" uuid NOT NULL
);

INSERT INTO "docker_manifest_dedupe" ("id", "survivor_id")
SELECT m."id",
       (SELECT s."id"
        FROM "docker_manifest" s
        WHERE s."image_id" = m."image_id"
          AND s."digest" = m."digest"
        ORDER BY CASE WHEN s."created_at" IS NULL THEN 1 ELSE 0 END, s."created_at", s."id"
        LIMIT 1)
FROM "docker_manifest" m;

-- DOCKER MANIFEST CHILD: the manifests an index references, with the platform the index names them
-- under. Built from the old shape: a multi-platform tag held the index in its 'Multiplatform' tag
-- platform and one tracking row per child in the tag's other tag platforms.
CREATE TABLE "docker_manifest_child"
(
    "parent_id" uuid         NOT NULL,
    "child_id"  uuid         NOT NULL,
    "platform"  varchar(255) NOT NULL,
    PRIMARY KEY ("parent_id", "child_id"),
    CONSTRAINT "fk_docker_manifest_child__parent_id"
        FOREIGN KEY ("parent_id") REFERENCES "docker_manifest" ("id") ON DELETE CASCADE,
    CONSTRAINT "fk_docker_manifest_child__child_id"
        FOREIGN KEY ("child_id") REFERENCES "docker_manifest" ("id") ON DELETE CASCADE
);

INSERT INTO "docker_manifest_child" ("parent_id", "child_id", "platform")
SELECT dp."survivor_id", dc."survivor_id", MIN(c."platform")
FROM "docker_manifest" p
         JOIN "docker_manifest_dedupe" dp ON dp."id" = p."id"
         JOIN "docker_tag_platform" ptp ON ptp."id" = p."tag_platform_id"
         JOIN "docker_tag_platform" ctp ON ctp."tag_id" = ptp."tag_id" AND ctp."id" <> ptp."id"
         JOIN "docker_manifest" c ON c."tag_platform_id" = ctp."id"
         JOIN "docker_manifest_dedupe" dc ON dc."id" = c."id"
WHERE p."platform" = 'Multiplatform'
  AND c."platform" <> 'Multiplatform'
  AND dp."survivor_id" <> dc."survivor_id"
GROUP BY dp."survivor_id", dc."survivor_id";

-- A survivor that lost its layer links (its tag was re-pointed by the old code) takes them over from
-- the rows folded into it.
INSERT INTO "docker_manifest_layer" ("manifest_id", "layer_id")
SELECT DISTINCT d."survivor_id", ml."layer_id"
FROM "docker_manifest_layer" ml
         JOIN "docker_manifest_dedupe" d ON d."id" = ml."manifest_id"
WHERE d."id" <> d."survivor_id"
  AND NOT EXISTS (SELECT 1 FROM "docker_manifest_layer" x WHERE x."manifest_id" = d."survivor_id");

DELETE FROM "docker_manifest"
WHERE "id" IN (SELECT "id" FROM "docker_manifest_dedupe" WHERE "id" <> "survivor_id");

DROP TABLE "docker_manifest_dedupe";

-- A tag named 'sha512:<hex>' records a sha512 push (RPS-1242, RPS-1244): the registry checked the name
-- against the bytes, so it is the sha512 digest of its manifest (the tag's own digest is the sha256 one).
-- Keep it on the manifest, so that reference keeps resolving before the repair service fills the rest.
UPDATE "docker_manifest"
SET "digest_sha512" = (SELECT MIN(t."name")
                       FROM "docker_tag" t
                       WHERE t."image_id" = "docker_manifest"."image_id"
                         AND t."digest" = "docker_manifest"."digest"
                         AND t."name" LIKE 'sha512:%')
WHERE EXISTS (SELECT 1
              FROM "docker_tag" t
              WHERE t."image_id" = "docker_manifest"."image_id"
                AND t."digest" = "docker_manifest"."digest"
                AND t."name" LIKE 'sha512:%');

-- Tags named after a digest were bookkeeping of a digest-pushed index (and of a sha512 push), never
-- something a client tags. Their manifests stay, pullable by digest.
DELETE FROM "docker_tag" WHERE "name" LIKE 'sha256:%' OR "name" LIKE 'sha512:%';

-- (image, name) is unique from now on. The code always upserted by name, so this only removes
-- duplicates that older versions may have left; the most recently written tag wins.
DELETE FROM "docker_tag"
WHERE "id" IN (SELECT t."id"
               FROM "docker_tag" t
               WHERE EXISTS (SELECT 1
                             FROM "docker_tag" o
                             WHERE o."image_id" = t."image_id"
                               AND o."name" = t."name"
                               AND o."id" <> t."id"
                               AND (COALESCE(o."last_updated_at", o."created_at", TIMESTAMP '1970-01-01 00:00:00') >
                                    COALESCE(t."last_updated_at", t."created_at", TIMESTAMP '1970-01-01 00:00:00')
                                 OR (COALESCE(o."last_updated_at", o."created_at", TIMESTAMP '1970-01-01 00:00:00') =
                                     COALESCE(t."last_updated_at", t."created_at", TIMESTAMP '1970-01-01 00:00:00')
                                     AND o."id" > t."id"))));

UPDATE "docker_tag"
SET "manifest_id" = (SELECT m."id"
                     FROM "docker_manifest" m
                     WHERE m."image_id" = "docker_tag"."image_id"
                       AND m."digest" = "docker_tag"."digest");

-- A tag whose manifest row is gone answered 404 already: nothing can be pulled through it.
DELETE FROM "docker_tag" WHERE "manifest_id" IS NULL;

ALTER TABLE "docker_manifest" ALTER COLUMN "image_id" SET NOT NULL;
ALTER TABLE "docker_manifest"
    ADD CONSTRAINT "fk_docker_manifest__image_id"
        FOREIGN KEY ("image_id") REFERENCES "docker_image" ("id") ON DELETE CASCADE;
CREATE UNIQUE INDEX "ux_docker_manifest__image_id_digest" ON "docker_manifest" ("image_id", "digest");
CREATE INDEX "idx_docker_manifest__digest_sha512" ON "docker_manifest" ("digest_sha512");
CREATE INDEX "idx_docker_manifest__storage_name" ON "docker_manifest" ("storage_name");

ALTER TABLE "docker_tag" ALTER COLUMN "manifest_id" SET NOT NULL;
ALTER TABLE "docker_tag"
    ADD CONSTRAINT "fk_docker_tag__manifest_id"
        FOREIGN KEY ("manifest_id") REFERENCES "docker_manifest" ("id") ON DELETE CASCADE;
CREATE INDEX "idx_docker_tag__manifest_id" ON "docker_tag" ("manifest_id");

DROP INDEX "ux_docker_tag__image_id_name_platform";
CREATE UNIQUE INDEX "ux_docker_tag__image_id_name" ON "docker_tag" ("image_id", "name");

CREATE INDEX "idx_docker_manifest_child__child_id" ON "docker_manifest_child" ("child_id");
