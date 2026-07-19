/**
 * hakken kotoba — ingest (product-discovery write path).
 *
 * Per ADR-2606011700 (override of 2606011400). Replaces vendor's
 * `supplier_search.py` + kotoba-datom / RisingWave `vertex_hakken_*` writes
 * with on-chain content-addressed AT records via @etzhayyim/sdk:
 *
 *   createKyselyDb().insertInto("vertex_hakken_*").values({...})
 *     → e.write({ collection, record, rkey })
 *
 * Idempotency via rkey (productSlug / itemId), mirroring the tsukuru
 * factoryRegistry pattern (rkey = slug). Re-ingest upserts.
 *
 * Ingest CORE only. Phase fulfillment (dropship / import / OEM order,
 * okaimono register, Stripe product creation) HITS the Settlement axis and
 * stays a etzhayyim function (ADR-2606011400); it is intentionally absent here.
 */

import type { Etzhayyim } from "@etzhayyim/sdk";
import {
  BRANDED_PRODUCT_COLLECTION,
  SUPPLIER_CANDIDATE_COLLECTION,
  type BrandedProductRecord,
  type CandidateView,
  type IngestProductInput,
  type IngestProductOutput,
  type IngestSupplierCandidateInput,
  type IngestSupplierCandidateOutput,
  type ListProductsInput,
  type ListProductsOutput,
  type ListSupplierCandidatesInput,
  type ListSupplierCandidatesOutput,
  type ProductView,
  type SupplierCandidateRecord,
} from "./types.js";

const VALID_PLATFORMS = new Set(["aliexpress", "alibaba", "1688"]);

export async function ingestProduct(
  e: Etzhayyim,
  input: IngestProductInput
): Promise<IngestProductOutput> {
  if (!input.productSlug || !input.name || !input.brand || !input.category) {
    return { status: "rejected", productSlug: input.productSlug ?? "", error: "missingRequiredFields" };
  }
  if (!Number.isInteger(input.priceJpy) || input.priceJpy < 0) {
    return { status: "rejected", productSlug: input.productSlug, error: "invalidPriceJpy" };
  }

  const existing = await e
    .read<BrandedProductRecord>({ collection: BRANDED_PRODUCT_COLLECTION, rkey: input.productSlug })
    .catch(() => ({ records: [] as Array<{ uri: string; value: BrandedProductRecord }> }));
  const isUpsert = Boolean(existing.records[0]?.value);

  const record: BrandedProductRecord = {
    productSlug: input.productSlug,
    name: input.name,
    brand: input.brand,
    category: input.category,
    priceJpy: input.priceJpy,
    url: input.url,
    material: input.material,
    ingestedAt: new Date().toISOString(),
  };

  const receipt = await e.write({
    collection: BRANDED_PRODUCT_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey: input.productSlug,
  });

  return {
    status: isUpsert ? "upserted" : "ingested",
    productSlug: input.productSlug,
    uri: receipt.uri,
  };
}

export async function ingestSupplierCandidate(
  e: Etzhayyim,
  input: IngestSupplierCandidateInput
): Promise<IngestSupplierCandidateOutput> {
  if (!input.itemId || !input.name || !input.supplierCountryIso3) {
    return { status: "rejected", itemId: input.itemId ?? "", error: "missingRequiredFields" };
  }
  if (!VALID_PLATFORMS.has(input.platform)) {
    return { status: "rejected", itemId: input.itemId, error: "invalidPlatform" };
  }
  if (!Number.isInteger(input.priceJpy) || !Number.isInteger(input.weightG)) {
    return { status: "rejected", itemId: input.itemId, error: "nonIntegerNumeric" };
  }
  if (!Number.isInteger(input.ratingMilli) || input.ratingMilli < 0 || input.ratingMilli > 5000) {
    return { status: "rejected", itemId: input.itemId, error: "invalidRatingMilli" };
  }

  const existing = await e
    .read<SupplierCandidateRecord>({ collection: SUPPLIER_CANDIDATE_COLLECTION, rkey: input.itemId })
    .catch(() => ({ records: [] as Array<{ uri: string; value: SupplierCandidateRecord }> }));
  const isUpsert = Boolean(existing.records[0]?.value);

  const record: SupplierCandidateRecord = {
    itemId: input.itemId,
    platform: input.platform,
    name: input.name,
    url: input.url,
    priceJpy: input.priceJpy,
    weightG: input.weightG,
    ratingMilli: input.ratingMilli,
    reviewCount: input.reviewCount,
    material: input.material,
    thicknessCm: input.thicknessCm,
    washable: input.washable,
    leadDays: input.leadDays,
    minOrder: input.minOrder,
    supplierCountryIso3: input.supplierCountryIso3,
    equivalentOfSlug: input.equivalentOfSlug,
    ingestedAt: new Date().toISOString(),
  };

  const receipt = await e.write({
    collection: SUPPLIER_CANDIDATE_COLLECTION,
    record: record as unknown as Record<string, unknown>,
    rkey: input.itemId,
  });

  return {
    status: isUpsert ? "upserted" : "ingested",
    itemId: input.itemId,
    uri: receipt.uri,
  };
}

export async function listProducts(
  e: Etzhayyim,
  input: ListProductsInput = {}
): Promise<ListProductsOutput> {
  const limit = Math.min(input.limit ?? 50, 100);
  const resp = await e.read<BrandedProductRecord>({
    collection: BRANDED_PRODUCT_COLLECTION,
    cursor: input.cursor,
    limit,
  });

  const items: ProductView[] = resp.records
    .filter((r) => (input.category ? r.value.category === input.category : true))
    .map((r) => ({ ...r.value, uri: r.uri }));

  return { items, cursor: resp.cursor, limit };
}

export async function listSupplierCandidates(
  e: Etzhayyim,
  input: ListSupplierCandidatesInput = {}
): Promise<ListSupplierCandidatesOutput> {
  const limit = Math.min(input.limit ?? 50, 100);
  const resp = await e.read<SupplierCandidateRecord>({
    collection: SUPPLIER_CANDIDATE_COLLECTION,
    cursor: input.cursor,
    limit,
  });

  const items: CandidateView[] = resp.records
    .filter((r) => {
      const v = r.value;
      if (input.platform && v.platform !== input.platform) return false;
      if (input.equivalentOfSlug && v.equivalentOfSlug !== input.equivalentOfSlug) return false;
      return true;
    })
    .map((r) => ({ ...r.value, uri: r.uri }));

  return { items, cursor: resp.cursor, limit };
}
