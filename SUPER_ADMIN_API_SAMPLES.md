# Super Admin SaaS API Samples

Base path: `/api/saas/admin`

Auth header:

```text
Authorization: Bearer <super-admin-jwt>
```

## 1. Dashboard

### `GET /api/saas/admin/dashboard`

Response:

```json
{
  "totalShops": 24,
  "activeShops": 18,
  "expiredShops": 4,
  "totalRevenueThisMonth": 185000.0
}
```

## 2. View All Shops

### `GET /api/saas/admin/shops?page=0&size=10&search=kamal&status=expired`

Query params:

```text
page   -> default 0
size   -> default 10
search -> optional tenantId / shopName / adminUsername search
status -> all | active | expired | blocked | inactive
```

Response:

```json
{
  "items": [
    {
      "tenantId": "kamal-stores",
      "shopName": "Kamal Stores",
      "adminUsername": "kamal_admin",
      "planName": "MONTHLY_BASIC",
      "active": true,
      "blocked": false,
      "maxBranches": 1,
      "extraBranches": 2,
      "allowedBranches": 3,
      "currentBranchCount": 2,
      "validUntil": "2026-04-25T10:00:00",
      "createdAt": "2026-03-25T10:00:00"
    },
    {
      "tenantId": "nimal-mart",
      "shopName": "Nimal Mart",
      "adminUsername": "nimal_admin",
      "planName": "LIFETIME_YEARLY",
      "active": true,
      "blocked": true,
      "maxBranches": 1,
      "extraBranches": 0,
      "allowedBranches": 1,
      "currentBranchCount": 1,
      "validUntil": "2027-03-25T10:00:00",
      "createdAt": "2026-01-10T08:30:00"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 24,
  "totalPages": 3,
  "first": true,
  "last": false
}
```

## 3. Single Shop View

### `GET /api/saas/admin/shops/{tenantId}`

Example:

```text
GET /api/saas/admin/shops/kamal-stores
```

Response:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "planName": "MONTHLY_BASIC",
  "active": true,
  "blocked": false,
  "maxBranches": 1,
  "extraBranches": 2,
  "allowedBranches": 3,
  "currentBranchCount": 2,
  "validUntil": "2026-05-25T10:00:00",
  "createdAt": "2026-03-25T10:00:00",
  "notes": "Monthly renewal paid to bank",
  "mainBranchName": "Main Branch",
  "mainBranchAddress": "No. 120, Main Street, Galle",
  "mainBranchPhone": "0771234567"
}
```

## 4. Add New Shop

### `POST /api/saas/admin/shops`

Request:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "adminPassword": "kamal@123",
  "planId": 1,
  "amountPaid": 2000.0,
  "initialBranchName": "Main Branch",
  "initialBranchAddress": "No. 120, Main Street, Galle",
  "initialBranchPhone": "0771234567",
  "note": "Initial onboarding payment received via bank transfer"
}
```

Response:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "planName": "MONTHLY_BASIC",
  "active": true,
  "blocked": false,
  "maxBranches": 1,
  "extraBranches": 0,
  "allowedBranches": 1,
  "currentBranchCount": 1,
  "validUntil": "2026-04-25T10:00:00",
  "createdAt": "2026-03-25T10:00:00"
}
```

## 5. Block / Unblock Shop

### `PATCH /api/saas/admin/shops/{tenantId}/block`

Block request:

```json
{
  "blocked": true,
  "reason": "Temporary block due to terms violation"
}
```

Unblock request:

```json
{
  "blocked": false,
  "reason": "Issue resolved"
}
```

Response:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "planName": "MONTHLY_BASIC",
  "active": true,
  "blocked": true,
  "maxBranches": 1,
  "extraBranches": 0,
  "allowedBranches": 1,
  "currentBranchCount": 1,
  "validUntil": "2026-04-25T10:00:00",
  "createdAt": "2026-03-25T10:00:00"
}
```

## 6. Reset Admin Password

### `PATCH /api/saas/admin/shops/{tenantId}/admin-password`

Request:

```json
{
  "newPassword": "newSecure@123"
}
```

Response:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "planName": "MONTHLY_BASIC",
  "active": true,
  "blocked": false,
  "maxBranches": 1,
  "extraBranches": 0,
  "allowedBranches": 1,
  "currentBranchCount": 1,
  "validUntil": "2026-04-25T10:00:00",
  "createdAt": "2026-03-25T10:00:00"
}
```

## 7. Manual Renew

### `POST /api/saas/admin/shops/{tenantId}/renew`

Request:

```json
{
  "cycles": 1,
  "amountPaid": 2000.0,
  "note": "Monthly renewal paid to bank"
}
```

Response:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "planName": "MONTHLY_BASIC",
  "active": true,
  "blocked": false,
  "maxBranches": 1,
  "extraBranches": 0,
  "allowedBranches": 1,
  "currentBranchCount": 1,
  "validUntil": "2026-05-25T10:00:00",
  "createdAt": "2026-03-25T10:00:00"
}
```

## 8. Upgrade / Downgrade Package

### `POST /api/saas/admin/shops/{tenantId}/package`

Request:

```json
{
  "planId": 3,
  "amountPaid": 40000.0,
  "note": "Upgraded from monthly plan to yearly lifetime plan"
}
```

Response:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "planName": "LIFETIME_YEARLY",
  "active": true,
  "blocked": false,
  "maxBranches": 1,
  "extraBranches": 0,
  "allowedBranches": 1,
  "currentBranchCount": 1,
  "validUntil": "2027-04-25T10:00:00",
  "createdAt": "2026-03-25T10:00:00"
}
```

## 9. Add Extra Branches

### `POST /api/saas/admin/shops/{tenantId}/extra-branches`

Request:

```json
{
  "extraBranches": 2,
  "amountPaid": 5000.0,
  "note": "Customer paid for two extra branches"
}
```

Response:

```json
{
  "tenantId": "kamal-stores",
  "shopName": "Kamal Stores",
  "adminUsername": "kamal_admin",
  "planName": "MONTHLY_BASIC",
  "active": true,
  "blocked": false,
  "maxBranches": 1,
  "extraBranches": 2,
  "allowedBranches": 3,
  "currentBranchCount": 1,
  "validUntil": "2026-04-25T10:00:00",
  "createdAt": "2026-03-25T10:00:00"
}
```

## 10. Shop Payment History

### `GET /api/saas/admin/shops/{tenantId}/payments`

Example:

```text
GET /api/saas/admin/shops/kamal-stores/payments
```

Response:

```json
[
  {
    "id": 12,
    "actionType": "RENEWAL",
    "amount": 2000.0,
    "note": "Monthly renewal paid to bank",
    "performedBy": "chala_admin",
    "createdAt": "2026-04-25T11:30:00"
  },
  {
    "id": 7,
    "actionType": "EXTRA_BRANCHES",
    "amount": 5000.0,
    "note": "Customer paid for two extra branches",
    "performedBy": "chala_admin",
    "createdAt": "2026-04-01T09:15:00"
  },
  {
    "id": 3,
    "actionType": "ONBOARDING",
    "amount": 2000.0,
    "note": "Initial onboarding payment received via bank transfer",
    "performedBy": "chala_admin",
    "createdAt": "2026-03-25T10:00:00"
  }
]
```

## Notes

Validation errors follow the app-wide `ProblemDetail` format.
