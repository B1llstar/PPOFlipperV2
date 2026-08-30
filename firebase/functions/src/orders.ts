/**
 * Order schema shared with GE Star V2's Firestore sync layer
 * (plugins/ge-star-v2/.../gestarv2/GeStarFirestoreSync.java). Field names and the
 * GeStarOrder.Status values must stay in lockstep with that class - this is the
 * contract between the web UI and the in-game plugin that actually executes trades.
 */

export type OrderAction = "BUY" | "SELL";

// Mirrors net.runelite.client.plugins.microbot.gestarv2.GeStarOrder.Status
export type OrderStatus = "QUEUED" | "SUBMITTED" | "DONE" | "SKIPPED" | "FAILED";

export interface OrderDoc {
  ownerUid: string;
  action: OrderAction;
  itemName: string;
  quantity: number;
  price: number;
  status: OrderStatus;
  quantityFilled: number;
  statusDetail: string | null;
  createdAt: FirebaseFirestore.Timestamp;
  updatedAt: FirebaseFirestore.Timestamp;
}

export const ORDERS_COLLECTION = "orders";

export const MAX_ITEM_NAME_LENGTH = 100;
export const MAX_QUANTITY = 2_000_000_000;
export const MAX_PRICE = 2_000_000_000;

export class ValidationError extends Error {}

export interface CreateOrderInput {
  action: unknown;
  itemName: unknown;
  quantity: unknown;
  price: unknown;
}

/** Validates raw callable-function input into a well-typed order request. Throws ValidationError on anything malformed - never trust client input to already be well-shaped. */
export function validateCreateOrderInput(input: CreateOrderInput): {
  action: OrderAction;
  itemName: string;
  quantity: number;
  price: number;
} {
  if (input.action !== "BUY" && input.action !== "SELL") {
    throw new ValidationError("action must be \"BUY\" or \"SELL\"");
  }

  if (typeof input.itemName !== "string") {
    throw new ValidationError("itemName must be a string");
  }
  const itemName = input.itemName.trim();
  if (itemName.length === 0) {
    throw new ValidationError("itemName must not be empty");
  }
  if (itemName.length > MAX_ITEM_NAME_LENGTH) {
    throw new ValidationError(`itemName must be at most ${MAX_ITEM_NAME_LENGTH} characters`);
  }

  const quantity = input.quantity;
  if (typeof quantity !== "number" || !Number.isInteger(quantity) || quantity <= 0 || quantity > MAX_QUANTITY) {
    throw new ValidationError(`quantity must be an integer between 1 and ${MAX_QUANTITY}`);
  }

  const price = input.price;
  if (typeof price !== "number" || !Number.isInteger(price) || price <= 0 || price > MAX_PRICE) {
    throw new ValidationError(`price must be an integer between 1 and ${MAX_PRICE}`);
  }

  return { action: input.action, itemName, quantity, price };
}
