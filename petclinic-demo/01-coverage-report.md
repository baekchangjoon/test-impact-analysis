## 1. Per-test impact range — what each black-box test actually exercises

| Test case | Result | Prod files | Covered lines | Top files (lines) |
|---|---|--:|--:|---|
| `AuthApiBlackBoxIT#loginWithInvalidCredentialsReturns400` | PASSED | 2 | 16 | `AuthController.java`(13), `CustomUserDetailsService.java`(3) |
| `AuthApiBlackBoxIT#loginWithValidCredentialsReturnsToken` | PASSED | 3 | 30 | `AuthController.java`(17), `JwtUtil.java`(10), `CustomUserDetailsService.java`(3) |
| `AuthApiBlackBoxIT#validateInvalidTokenReturnsValidFalse` | PASSED | 2 | 8 | `AuthController.java`(7), `JwtUtil.java`(1) |
| `AuthApiBlackBoxIT#validateValidTokenReturnsValidTrue` | PASSED | 2 | 17 | `JwtUtil.java`(9), `AuthController.java`(8) |
| `ConcurrencyBlackBoxIT#concurrentOwnerCreatesAllSucceedWithDistinctIds` | PASSED | 4 | 24 | `Owner.java`(12), `Person.java`(7), `BaseEntity.java`(3) |
| `ConcurrencyBlackBoxIT#concurrentPetCreatesForSameOwnerAreAllPersisted` | PASSED | 9 | 44 | `Owner.java`(9), `Pet.java`(9), `PetRestController.java`(6) |
| `OwnerApiBlackBoxIT#createOwner` | PASSED | 4 | 24 | `Owner.java`(12), `Person.java`(7), `BaseEntity.java`(3) |
| `OwnerApiBlackBoxIT#filterOwnersByLastName` | PASSED | 8 | 28 | `Owner.java`(6), `OwnerRestController.java`(5), `Pet.java`(5) |
| `OwnerApiBlackBoxIT#getMissingOwnerReturns404` | PASSED | 1 | 2 | `OwnerRestController.java`(2) |
| `OwnerApiBlackBoxIT#getOwnerById` | PASSED | 7 | 22 | `Owner.java`(6), `Pet.java`(5), `BaseEntity.java`(3) |
| `OwnerApiBlackBoxIT#listAllOwners` | PASSED | 8 | 28 | `Owner.java`(6), `Pet.java`(5), `Visit.java`(5) |
| `OwnerApiBlackBoxIT#listingOwnersRequiresAuthentication` | PASSED | 0 | 0 | — |
| `OwnerApiBlackBoxIT#updateMissingOwnerReturns404` | PASSED | 4 | 16 | `Owner.java`(8), `Person.java`(5), `OwnerRestController.java`(2) |
| `OwnerApiBlackBoxIT#updateOwner` | PASSED | 4 | 30 | `Owner.java`(12), `Person.java`(7), `OwnerRestController.java`(6) |
| `PetApiBlackBoxIT#createPet` | PASSED | 7 | 32 | `Pet.java`(9), `Owner.java`(6), `PetRestController.java`(6) |
| `PetApiBlackBoxIT#getMissingPetReturns404` | PASSED | 8 | 25 | `Owner.java`(9), `PetRestController.java`(5), `BaseEntity.java`(3) |
| `PetApiBlackBoxIT#getPetById` | PASSED | 8 | 28 | `Owner.java`(9), `Pet.java`(5), `PetRestController.java`(4) |
| `PetApiBlackBoxIT#getPetTypes` | PASSED | 7 | 37 | `AuthController.java`(17), `JwtUtil.java`(10), `BaseEntity.java`(3) |
| `PetApiBlackBoxIT#readingPetsRequiresAuthentication` | PASSED | 0 | 0 | — |
| `VetApiBlackBoxIT#filterVetsBySpecialty` | PASSED | 6 | 23 | `Vet.java`(7), `VetRestController.java`(7), `BaseEntity.java`(3) |
| `VetApiBlackBoxIT#getMissingVetReturnsEmptyBody` | PASSED | 6 | 7 | `BaseEntity.java`(2), `NamedEntity.java`(1), `Person.java`(1) |
| `VetApiBlackBoxIT#getVetById` | PASSED | 6 | 17 | `Vet.java`(7), `BaseEntity.java`(3), `Person.java`(3) |
| `VetApiBlackBoxIT#listAllVets` | PASSED | 6 | 19 | `Vet.java`(7), `BaseEntity.java`(3), `Person.java`(3) |
| `VetApiBlackBoxIT#listingVetsRequiresAuthentication` | PASSED | 0 | 0 | — |

## 2. Reverse selection index — touch a file, these tests must run

| Production file | # guarding tests | Tests |
|---|--:|---|
| `…/model/BaseEntity.java` | 16 | `Concurrency#concurrentOwnerCreatesAllSucceedWithDistinctIds`, `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#createOwner`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#getOwnerById`, `OwnerApi#listAllOwners`, `OwnerApi#updateMissingOwnerReturns404`, `OwnerApi#updateOwner`, `PetApi#createPet`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById`, `PetApi#getPetTypes`, `VetApi#filterVetsBySpecialty`, `VetApi#getMissingVetReturnsEmptyBody`, `VetApi#getVetById`, `VetApi#listAllVets` |
| `…/model/Person.java` | 15 | `Concurrency#concurrentOwnerCreatesAllSucceedWithDistinctIds`, `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#createOwner`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#getOwnerById`, `OwnerApi#listAllOwners`, `OwnerApi#updateMissingOwnerReturns404`, `OwnerApi#updateOwner`, `PetApi#createPet`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById`, `VetApi#filterVetsBySpecialty`, `VetApi#getMissingVetReturnsEmptyBody`, `VetApi#getVetById`, `VetApi#listAllVets` |
| `…/model/NamedEntity.java` | 12 | `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#getOwnerById`, `OwnerApi#listAllOwners`, `PetApi#createPet`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById`, `PetApi#getPetTypes`, `VetApi#filterVetsBySpecialty`, `VetApi#getMissingVetReturnsEmptyBody`, `VetApi#getVetById`, `VetApi#listAllVets` |
| `…/owner/Owner.java` | 11 | `Concurrency#concurrentOwnerCreatesAllSucceedWithDistinctIds`, `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#createOwner`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#getOwnerById`, `OwnerApi#listAllOwners`, `OwnerApi#updateMissingOwnerReturns404`, `OwnerApi#updateOwner`, `PetApi#createPet`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById` |
| `…/owner/OwnerRestController.java` | 9 | `Concurrency#concurrentOwnerCreatesAllSucceedWithDistinctIds`, `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#createOwner`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#getMissingOwnerReturns404`, `OwnerApi#getOwnerById`, `OwnerApi#listAllOwners`, `OwnerApi#updateMissingOwnerReturns404`, `OwnerApi#updateOwner` |
| `…/owner/PetType.java` | 8 | `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#getOwnerById`, `OwnerApi#listAllOwners`, `PetApi#createPet`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById`, `PetApi#getPetTypes` |
| `…/owner/Pet.java` | 7 | `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#getOwnerById`, `OwnerApi#listAllOwners`, `PetApi#createPet`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById` |
| `…/owner/PetRestController.java` | 5 | `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `PetApi#createPet`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById`, `PetApi#getPetTypes` |
| `…/owner/Visit.java` | 5 | `Concurrency#concurrentPetCreatesForSameOwnerAreAllPersisted`, `OwnerApi#filterOwnersByLastName`, `OwnerApi#listAllOwners`, `PetApi#getMissingPetReturns404`, `PetApi#getPetById` |
| `…/security/AuthController.java` | 5 | `AuthApi#loginWithInvalidCredentialsReturns400`, `AuthApi#loginWithValidCredentialsReturnsToken`, `AuthApi#validateInvalidTokenReturnsValidFalse`, `AuthApi#validateValidTokenReturnsValidTrue`, `PetApi#getPetTypes` |
| `…/security/JwtUtil.java` | 4 | `AuthApi#loginWithValidCredentialsReturnsToken`, `AuthApi#validateInvalidTokenReturnsValidFalse`, `AuthApi#validateValidTokenReturnsValidTrue`, `PetApi#getPetTypes` |
| `…/vet/Specialty.java` | 4 | `VetApi#filterVetsBySpecialty`, `VetApi#getMissingVetReturnsEmptyBody`, `VetApi#getVetById`, `VetApi#listAllVets` |
| `…/vet/Vet.java` | 4 | `VetApi#filterVetsBySpecialty`, `VetApi#getMissingVetReturnsEmptyBody`, `VetApi#getVetById`, `VetApi#listAllVets` |
| `…/vet/VetRestController.java` | 4 | `VetApi#filterVetsBySpecialty`, `VetApi#getMissingVetReturnsEmptyBody`, `VetApi#getVetById`, `VetApi#listAllVets` |
| `…/security/CustomUserDetailsService.java` | 3 | `AuthApi#loginWithInvalidCredentialsReturns400`, `AuthApi#loginWithValidCredentialsReturnsToken`, `PetApi#getPetTypes` |

## 3. Coverage hotspots & reach

**Widest-reach tests** (most production files touched):
- `ConcurrencyBlackBoxIT#concurrentPetCreatesForSameOwnerAreAllPersisted` → 9 files, 44 lines
- `OwnerApiBlackBoxIT#filterOwnersByLastName` → 8 files, 28 lines
- `OwnerApiBlackBoxIT#listAllOwners` → 8 files, 28 lines
- `PetApiBlackBoxIT#getMissingPetReturns404` → 8 files, 25 lines
- `PetApiBlackBoxIT#getPetById` → 8 files, 28 lines

**Narrowest-reach tests:**
- `AuthApiBlackBoxIT#validateValidTokenReturnsValidTrue` → 2 files, 17 lines
- `OwnerApiBlackBoxIT#getMissingOwnerReturns404` → 1 files, 2 lines
- `OwnerApiBlackBoxIT#listingOwnersRequiresAuthentication` → 0 files, 0 lines
- `PetApiBlackBoxIT#readingPetsRequiresAuthentication` → 0 files, 0 lines
- `VetApiBlackBoxIT#listingVetsRequiresAuthentication` → 0 files, 0 lines

**Most-shared production files** (highest fan-in = riskiest to change):
- `…/model/BaseEntity.java` ← guarded by 16 tests
- `…/model/Person.java` ← guarded by 15 tests
- `…/model/NamedEntity.java` ← guarded by 12 tests
- `…/owner/Owner.java` ← guarded by 11 tests
- `…/owner/OwnerRestController.java` ← guarded by 9 tests
- `…/owner/PetType.java` ← guarded by 8 tests
- `…/owner/Pet.java` ← guarded by 7 tests
- `…/security/AuthController.java` ← guarded by 5 tests

## 4. Black-box blind spots — 25/40 production files untouched by any black-box test

- `org/springframework/data/util/TypeInformation.java`
- `…/PetClinicApplication.java`
- `…/PetClinicRuntimeHints.java`
- `…/config/OpenApiConfig.java`
- `…/model/package-info.java`
- `…/owner/OwnerController.java`
- `…/owner/OwnerRepository.java`
- `…/owner/PetController.java`
- `…/owner/PetTypeFormatter.java`
- `…/owner/PetTypeRepository.java`
- `…/owner/PetValidator.java`
- `…/owner/VisitController.java`
- `…/owner/package-info.java`
- `…/package-info.java`
- `…/security/JwtAuthenticationFilter.java`
- `…/security/SecurityConfig.java`
- `…/system/CacheConfiguration.java`
- `…/system/CrashController.java`
- `…/system/WebConfiguration.java`
- `…/system/WelcomeController.java`
- `…/system/package-info.java`
- `…/vet/VetController.java`
- `…/vet/VetRepository.java`
- `…/vet/Vets.java`
- `…/vet/package-info.java`

---
**Totals:** 24 tests · 15 production files covered · 477 (test·line) coverage points
