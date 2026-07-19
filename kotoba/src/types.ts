/**
 * hakken kotoba — record types aligned to Lexicon.
 *
 * Mirrors the lexicons at:
 *   00-contracts/lexicons/com/etzhayyim/apps/hakken/ingestProduct.json
 *   00-contracts/lexicons/com/etzhayyim/apps/hakken/ingestSupplierCandidate.json
 *   00-contracts/lexicons/com/etzhayyim/apps/hakken/listProducts.json
 *   00-contracts/lexicons/com/etzhayyim/apps/hakken/listSupplierCandidates.json
 *
 * Per ADR-2606011700 (hakken etzhayyim migration, override of 2606011400):
 * replaces vendor's kotoba-datom + RisingWave `vertex_hakken_*` writes with
 * on-chain content-addressed AT records via @etzhayyim/sdk.
 *
 * AT Lexicon has no float type, so vendor floats are integer-encoded:
 *   weight_kg  → weightG     (× 1000, grams)
 *   rating     → ratingMilli (× 1000, 0-5000)
 */

export const HAKKEN_DID = "did:web:hakken.etzhayyim.com" as const;

export const BRANDED_PRODUCT_COLLECTION =
  "com.etzhayyim.apps.hakken.brandedProduct" as const;
export const SUPPLIER_CANDIDATE_COLLECTION =
  "com.etzhayyim.apps.hakken.supplierCandidate" as const;

export type SupplierPlatform = "aliexpress" | "alibaba" | "1688";

/** Record body for `com.etzhayyim.apps.hakken.brandedProduct`. */
export interface BrandedProductRecord {
  productSlug: string;
  name: string;
  brand: string;
  category: string;
  priceJpy: number;
  url?: string;
  material?: string;
  ingestedAt: string;
}

/** Record body for `com.etzhayyim.apps.hakken.supplierCandidate`. */
export interface SupplierCandidateRecord {
  itemId: string;
  platform: SupplierPlatform;
  name: string;
  url?: string;
  priceJpy: number;
  /** Item weight in grams (vendor weight_kg × 1000). */
  weightG: number;
  /** Rating × 1000, 0-5000 (vendor rating × 1000). */
  ratingMilli: number;
  reviewCount: number;
  material?: string;
  thicknessCm?: number;
  washable?: boolean;
  leadDays?: number;
  minOrder?: number;
  supplierCountryIso3: string;
  /** productSlug of the branded product this candidate is an OEM equivalent of. */
  equivalentOfSlug?: string;
  ingestedAt: string;
}

// ─── ingestProduct ──────────────────────────────────────────────────

export interface IngestProductInput {
  productSlug: string;
  name: string;
  brand: string;
  category: string;
  priceJpy: number;
  url?: string;
  material?: string;
}

export interface IngestProductOutput {
  status: "ingested" | "upserted" | "rejected";
  productSlug: string;
  uri?: string;
  error?: string;
}

// ─── ingestSupplierCandidate ────────────────────────────────────────

export interface IngestSupplierCandidateInput {
  itemId: string;
  platform: SupplierPlatform;
  name: string;
  url?: string;
  priceJpy: number;
  weightG: number;
  ratingMilli: number;
  reviewCount: number;
  material?: string;
  thicknessCm?: number;
  washable?: boolean;
  leadDays?: number;
  minOrder?: number;
  supplierCountryIso3: string;
  equivalentOfSlug?: string;
}

export interface IngestSupplierCandidateOutput {
  status: "ingested" | "upserted" | "rejected";
  itemId: string;
  uri?: string;
  error?: string;
}

// ─── list views ─────────────────────────────────────────────────────

export interface ListProductsInput {
  category?: string;
  cursor?: string;
  limit?: number;
}

export interface ProductView extends BrandedProductRecord {
  uri: string;
}

export interface ListProductsOutput {
  items: ProductView[];
  cursor?: string;
  limit: number;
}

export interface ListSupplierCandidatesInput {
  platform?: SupplierPlatform;
  equivalentOfSlug?: string;
  cursor?: string;
  limit?: number;
}

export interface CandidateView extends SupplierCandidateRecord {
  uri: string;
}

export interface ListSupplierCandidatesOutput {
  items: CandidateView[];
  cursor?: string;
  limit: number;
}
