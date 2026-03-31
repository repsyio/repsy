export class CrateVersionInfo {
  public crateId: string;
  public name: string;
  public version: string;
  public readme: string;
  public license: string;
  public license_file: string;
  public documentation: string;
  public edition: string;
  public rust_version: string;
  public deps: CrateDependencyInfo[];
  public downloads: number;
  public created_at: Date;
}

export class CrateDependencyInfo {
  public name: string;
  public req: string;
  public features: string[];
  public optional: boolean;
  public default_features: boolean;
  public target: string;
  public kind: string;
  public registry: string;
  public package: string;
}
