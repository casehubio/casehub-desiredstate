# E-Commerce Tutorials — YAML Desired State

Learn the YAML desired-state language through a real-world online store.
Each tutorial builds on the previous one, adding new capabilities.

## Capability Matrix

| Feature | Tutorial 1 | Tutorial 2 | Tutorial 3 |
|---------|:----------:|:----------:|:----------:|
| **Nodes** — declare a service | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| **Dependencies** — ordering between services | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| **Variables** — environment-specific config | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| **Invariants** — structural safety checks | | :white_check_mark: | |
| **Rules** — auto-wiring missing pieces | | :white_check_mark: | |
| **Conditions** (`when:`) — optional features | | :white_check_mark: | |
| **Fault Policies** — escalation on failure | | :white_check_mark: | |
| **forEach** — stamp copies from a list | | | :white_check_mark: |
| **Iteration Groups** — aligned multi-node stamping | | | :white_check_mark: |
| **Modules** — reusable parameterised fragments | | | :white_check_mark: |
| **Module Imports** — composing modules with aliases | | | :white_check_mark: |
| **Module Invariants** — safety checks inside modules | | | :white_check_mark: |

## Example Matrix

| Example File | Domain Scenario | Nodes | Tests |
|---|---|---|---|
| `tutorial-1-store-basics.yaml` | Order flow: browse → cart → pay → confirm → ship | 5 | 5 |
| `tutorial-2-smart-defaults.yaml` | Fraud detection, auto-notifications, gift wrapping, payment escalation | 8 | 7 |
| `tutorial-3-scale-and-compose.yaml` | Multi-warehouse shipping, reusable notification module | 12 | 10 |
| `modules/order-notifications.yaml` | Email + SMS notification pair (imported by Tutorial 3) | 2 | — |

---

## Tutorial 1: Store Basics

**File:** `src/main/resources/META-INF/desiredstate/tutorial-1-store-basics.yaml`
**Test:** `Tutorial1StoreBasicsTest.java`

The simplest possible desired-state graph: five services that process an
order from browsing to delivery.

```
product-catalog → shopping-cart → payment → order-confirmation → shipping
```

**What you learn:**
- Declaring nodes with `type:` and `spec:`
- Wiring dependencies with `dependsOn:`
- Using `variables:` for environment-specific values (`${var.currency}`)

**Graph (5 nodes):**

| Node | Type | Depends On | Purpose |
|------|------|-----------|---------|
| `product-catalog` | product-catalog | — | The store's product listing |
| `shopping-cart` | shopping-cart | product-catalog | Where customers add items |
| `payment` | payment | shopping-cart | Charges the customer |
| `order-confirmation` | order-confirmation | payment | Sends the receipt |
| `shipping` | shipping | order-confirmation | Delivers the order |

---

## Tutorial 2: Smart Defaults

**File:** `src/main/resources/META-INF/desiredstate/tutorial-2-smart-defaults.yaml`
**Test:** `Tutorial2SmartDefaultsTest.java`

Building on Tutorial 1, adds four features that make the graph self-managing.

**What you learn:**

### Invariants — catch misconfigurations at startup
```yaml
invariants:
  payment-requires-fraud-check:
    match:
      pay: { type: payment }
    directDep:
      fraud: { type: fraud-check, of: pay, direction: DEPENDENTS }
    message: "Payment '${match.pay.id}' has no fraud check"
```
*"Every payment processor MUST have a fraud check."* Remove the `fraud-check`
node and the build fails immediately — no silent security holes.

### Rules — auto-wire missing pieces
```yaml
rules:
  auto-notify-confirmations:
    match:
      confirm: { type: order-confirmation }
    notExists:
      existing: { type: notification, of: confirm, direction: DEPENDENTS }
    actions:
      - addNode:
          id: "notify-${match.confirm.id}"
          type: notification
          spec: { channel: email, target: "${match.confirm.id}" }
      - addDependency:
          from: "notify-${match.confirm.id}"
          to: "${match.confirm.id}"
```
*"For every order confirmation without a notification, add one."* New
confirmation nodes automatically get email notifications — no manual wiring.

### Conditions — toggle optional features
```yaml
  gift-wrapping:
    type: gift-wrapping
    when: "${var.gift_wrapping_enabled}"
    dependsOn: [shopping-cart]
    spec: { style: premium, surcharge: 4.99 }
```
Set `gift_wrapping_enabled: "false"` and the node vanishes from the graph.

### Fault Policies — escalate failures
```yaml
faultPolicy:
  - faultTypes: [PROVISION_FAILED]
    nodeTypes: [payment]
    tiers:
      - threshold: 3
        reviewNode: { type: fraud-review, spec: { ... } }
      - threshold: 5
        reviewNode: { type: support-ticket, humanGating: ALL, spec: { ... } }
```
Payment fails 3 times → automated fraud review. 5 times → human support ticket.

**Graph (8 nodes):** 6 declared + 1 conditional (gift wrapping) + 1 rule-generated (notification).

---

## Tutorial 3: Scale & Compose

**File:** `src/main/resources/META-INF/desiredstate/tutorial-3-scale-and-compose.yaml`
**Module:** `src/main/resources/META-INF/desiredstate/modules/order-notifications.yaml`
**Test:** `Tutorial3ScaleAndComposeTest.java`

Scaling the store to multiple warehouses and composing reusable modules.

**What you learn:**

### forEach — stamp copies from a list
```yaml
iterations:
  warehouses:
    as: warehouse
    in: ["us-east", "eu-west", "ap-south"]

nodes:
  shipping:
    type: shipping
    forEach: warehouses
    dependsOn: [order-confirmation]
    spec:
      carrier: fedex
      warehouse: "${each.warehouse}"
      trackingEnabled: true
```
This creates three shipping nodes — `shipping.us-east`, `shipping.eu-west`,
`shipping.ap-south` — each with its own warehouse value. All three depend on
`order-confirmation`.

### Modules — reusable building blocks
```yaml
# modules/order-notifications.yaml
module:
  name: order-notifications
  parameters:
    watched_step: { type: string, required: true }
    email_template: { type: string, default: "standard" }
nodes:
  email:
    type: notification
    dependsOn: ["${var.watched_step}"]
    spec: { channel: email, target: "${var.watched_step}" }
  sms:
    type: notification
    dependsOn: ["${var.watched_step}"]
    spec: { channel: sms, target: "${var.watched_step}" }
```

Import it twice with different parameters:
```yaml
imports:
  - module: order-notifications
    as: payment-alerts
    parameters: { watched_step: payment }
  - module: order-notifications
    as: shipping-alerts
    parameters: { watched_step: order-confirmation }
```

This creates four notification nodes with aliased IDs:
- `payment-alerts.email`, `payment-alerts.sms` → depend on `payment`
- `shipping-alerts.email`, `shipping-alerts.sms` → depend on `order-confirmation`

**Graph (12 nodes):** 5 fixed + 3 forEach shipping + 4 module notifications.

---

## Java Annotation Companion

The `examples/webapp-annotated/` module implements the same tutorials
using Java annotations instead of YAML. This lets you compare both
surfaces side by side.

| Tutorial | YAML | Java Annotations |
|----------|------|-----------------|
| 1: Store Basics | `tutorial-1-store-basics.yaml` | `Tutorial1StoreBasics.java` — `@DesiredState` + `@Node` + `@DependsOn` |
| 2: Smart Defaults | `tutorial-2-smart-defaults.yaml` | `Tutorial2SmartDefaults.java` — `@GraphInvariant` + `@GraphRule` + `@FaultPolicyDef` |
| 3: Scale & Compose | `tutorial-3-scale-and-compose.yaml` | `Tutorial3ScaleAndCompose.java` — programmatic `GoalCompiler` (forEach/modules are YAML-only) |

Key differences:

| Concept | YAML | Java Annotations |
|---------|------|-----------------|
| Node declaration | `nodes: { cart: { type: shopping-cart } }` | `@Node("cart") default ShoppingCartSpec cart() { ... }` |
| Dependencies | `dependsOn: [catalog]` | `@DependsOn("catalog")` |
| Variables | `${var.currency}` | Constructor arguments (type-safe) |
| Conditional nodes | `when: "${var.enabled}"` | GoalCompiler decides which nodes to include |
| Invariants | `invariants:` block with pattern vocabulary | `@GraphInvariant` on static void method |
| Rules | `rules:` block with action templates | `@GraphRule` on static method returning `List<GraphMutation>` |
| Fault policies | `faultPolicy:` block with tier templates | `@FaultPolicyDef` + `@Tier` + review factory methods |
| forEach | `forEach: warehouses` | Explicit loop in GoalCompiler |
| Modules | `imports: [{ module: X, as: Y }]` | Helper method in GoalCompiler |

---

## Running the Tests

```bash
# All YAML tutorials
mvn test -pl examples/webapp-yaml

# All annotation tutorials
mvn test -pl examples/webapp-annotated

# A specific tutorial
mvn test -pl examples/webapp-yaml -Dtest=Tutorial1StoreBasicsTest
mvn test -pl examples/webapp-yaml -Dtest=Tutorial2SmartDefaultsTest
mvn test -pl examples/webapp-yaml -Dtest=Tutorial3ScaleAndComposeTest
```

## Shared NodeSpec Types

The `examples/webapp/` module defines 11 NodeSpec types used by all tutorials:

| Type ID | Record | Purpose |
|---------|--------|---------|
| `product-catalog` | `ProductCatalogSpec` | Store's product listing |
| `shopping-cart` | `ShoppingCartSpec` | Customer's cart |
| `payment` | `PaymentSpec` | Payment processing |
| `fraud-check` | `FraudCheckSpec` | Fraud detection |
| `order-confirmation` | `OrderConfirmationSpec` | Receipt / confirmation |
| `shipping` | `ShippingSpec` | Order delivery |
| `notification` | `NotificationSpec` | Email / SMS alerts |
| `gift-wrapping` | `GiftWrappingSpec` | Optional gift wrap |
| `loyalty` | `LoyaltySpec` | Rewards program |
| `fraud-review` | `FraudReviewSpec` | Automated fraud review (fault tier) |
| `support-ticket` | `SupportTicketSpec` | Human support escalation (fault tier) |
