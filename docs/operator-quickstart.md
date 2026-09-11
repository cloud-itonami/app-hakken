# app-hakken — operator quickstart

Everything below was run top-to-bottom from a fresh clone of this branch before it was
written down, and every figure quoted is the output that walk produced. If a step does
not behave as described, that is a defect in this document — please fix it here.

**What you get at the end:** the `lg-hakken` Clojure pipeline's test suite passing (46
tests / 153 assertions), and two verifiers telling you the truth about what is and is
not in this repository. **You do not get a running service.** Nothing here is deployed,
and `hakken.etzhayyim.com` does not resolve — see step 5.

## 0. Prerequisites

| Tool | Used for | Checked with |
|---|---|---|
| `git` | clone, and both verifiers | `git --version` |
| `bb` (babashka) | the test suite | `bb --version` |
| `nbb` | the two verifiers | `nbb --version` |

Walked with `bb` 1.12.218 and `nbb` 1.4.210. `bb` resolves two git dependencies on
first run, so step 2 needs network the first time. The verifiers in step 3 are offline.

> `bb` is this repository's existing harness, not a recommendation. The workspace has
> retired `bb` as a script host in favour of `nbb`; `lg-clj/bb.edn` and
> `lg-clj/run_tests.clj` predate that and have not been ported. Do not copy this
> pattern into a new repository.

## 1. Clone

```bash
git clone git@github.com:cloud-itonami/app-hakken.git
cd app-hakken
git ls-files | wc -l        # 39
```

39 tracked files: 33 preserved from the extraction, `README.edn` + `migration.edn`
added by it, and 4 files of documentation (this file, `README.md`, and the two
verifiers).

## 2. Run the test suite

```bash
cd lg-clj
kbb -M:test
```

Expected, and what the walk produced:

```
Testing lg-hakken.edn-and-cid-test
WARN lg-hakken.kotoba-datomic: KOTOBA_INTERNAL_SECRET is unset — requests carry NO
x-internal-trust header. ...

Testing lg-hakken.graph-test

Ran 46 tests containing 153 assertions.
0 failures, 0 errors.
```

Exit code 0.

**The `WARN` line is expected and is not a failure.** `lg-hakken.kotoba-datomic`
deliberately refuses to omit the `x-internal-trust` header silently: when
`KOTOBA_INTERNAL_SECRET` is unset it warns once on stderr rather than quietly sending
nothing. The suite itself never reaches the network — the network edges are dynamic
vars that the tests rebind to stubs.

The suite is offline. `kotoba.etzhayyim.com` is the *default* write target in
`lg-clj/src/lg_hakken/kotoba_datomic.cljc`, but nothing in the test run contacts it.

## 3. Run the two verifiers

From the repository root. Both are offline and deterministic.

```bash
kbb --backend sci docs/verify-docs-claims.cljk
```

```
SCANNED	39 tracked file / 16 src / 2 test / 46 deftest / 11 主張
  ok   ... (11 claims)
PASS — README.md の 11 個の主張は今日も成り立つ
```

Exit 0. This pins what `README.md` says is *absent* — no top-level `src/`, no lexicons,
no Python originals — because those are the claims that go silently wrong the moment
somebody starts implementing.

```bash
kbb --backend sci docs/verify-custody.cljk
```

```
SCANNED	33 保管ファイル / 6 追加物 / 3 検査
  FAIL 出所 tree（再構成 vs 記録）
         got  71b308d69e6286f1a91e2ac7ecf479de3627687e
         want dd2842a94e6da91ace3f6e0b4e9c6af96a59ea1c
  ok   保管ファイル数
         got  33
  FAIL 保管バイト数
         got  112333
         want 105537
FAIL — 保管対象が出所と一致しない
```

**Exit 1 is the expected result today, and it is the verifier working.** This
repository really has diverged from the extraction record `migration.edn` pins, at
commit `ae7298f`. `README.md` has the per-commit attribution. Do not "fix" this by
editing the recorded tree — resolving it is an owner decision.

Add `--origin` to also check the source tree against GitHub (needs network):

```bash
kbb --backend sci docs/verify-custody.cljk --origin
```

The extra line reports `ok 出所 GitHub の実 tree
（etzhayyim/root@e6439bcb:60-apps/etzhayyim-project-hakken）` — the record is accurate
about where this came from; it is the repository that moved.

### Reading the exit codes

Both verifiers use **0 = pass, 1 = fail, 3 = could not determine**. 3 is deliberately
neither: run either one outside a git repository and you get 3 with the underlying git
error preserved, never 0. A check that cannot run must not report success.

## 4. Confirm you left the tree clean

```bash
git status --porcelain     # empty
```

Neither the test run nor the verifiers write into the repository.

## 5. What you cannot do here, and why

- **You cannot deploy or reach this service.** `kotodama.jsonld` declares one route,
  `hakken.etzhayyim.com`, which is NXDOMAIN, as is its declared dependency
  `dispatcher.etzhayyim.com`. Check for yourself — this is a live fact, not a
  repository fact, which is why no verifier asserts it:

  ```bash
  dig +short hakken.etzhayyim.com        # empty (NXDOMAIN)
  dig +short kotoba.etzhayyim.com        # resolves
  ```

- **You cannot build `kotoba/`.** It is TypeScript with no lockfile and no build
  script in its `package.json`.

- **You cannot run `lg/`.** Three of the Python files the Clojure port names as its
  originals were not extracted; what remains does not constitute a runnable pipeline.

- **You cannot check the port against its oracle.** For the same reason: the Python
  `graph.py` / `edn.py` / `kotoba_datomic.py` the port claims fidelity to are not here.
