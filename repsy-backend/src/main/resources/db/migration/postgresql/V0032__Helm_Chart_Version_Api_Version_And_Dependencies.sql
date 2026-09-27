-- RPS-1557: index.yaml entries carry the apiVersion and the dependencies of Chart.yaml, so they are
-- kept with the chart version. Both are nullable: a chart stored before this script has neither
-- (its index entry stays as it was) until it is published again; the columns are not backfilled
-- because the values live only inside the chart archive.
ALTER TABLE "helm_chart_version" ADD COLUMN "api_version" varchar(32);
ALTER TABLE "helm_chart_version" ADD COLUMN "dependencies" text;
