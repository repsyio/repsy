-- RPS-1427: repo.searchable was never read and no form could set it; every repo has false.
ALTER TABLE "repo" DROP COLUMN "searchable";
