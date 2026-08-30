import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger } from "firebase-functions/v2";
import {
  CreateOrderInput,
  ORDERS_COLLECTION,
  OrderDoc,
  ValidationError,
  validateCreateOrderInput,
} from "./orders";

initializeApp();
const db = getFirestore();

const REGION = "us-central1";

/**
 * Creates a queued buy/sell order. This is the only write path into the orders
 * collection from client code - Firestore rules deny direct client writes, so every
 * order is validated here before GE Star V2 (polling Firestore from inside the game
 * client) ever sees it.
 */
export const createOrder = onCall({ region: REGION }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }

  let validated;
  try {
    validated = validateCreateOrderInput(request.data as CreateOrderInput);
  } catch (err) {
    if (err instanceof ValidationError) {
      throw new HttpsError("invalid-argument", err.message);
    }
    throw err;
  }

  const now = FieldValue.serverTimestamp();
  const doc: Omit<OrderDoc, "createdAt" | "updatedAt"> & {
    createdAt: FirebaseFirestore.FieldValue;
    updatedAt: FirebaseFirestore.FieldValue;
  } = {
    ownerUid: request.auth.uid,
    action: validated.action,
    itemName: validated.itemName,
    quantity: validated.quantity,
    price: validated.price,
    status: "QUEUED",
    quantityFilled: 0,
    statusDetail: null,
    createdAt: now,
    updatedAt: now,
  };

  const ref = await db.collection(ORDERS_COLLECTION).add(doc);
  logger.info("Order created", { orderId: ref.id, uid: request.auth.uid, action: validated.action, itemName: validated.itemName });

  return { orderId: ref.id };
});

/**
 * Cancels an order the caller owns. Only QUEUED orders can be cancelled outright -
 * a SUBMITTED order already has a live GE offer behind it in-game, so cancelling it
 * here would desync from what GE Star V2 is actually doing. abortOffer support (via
 * a separate "cancel requested" flag GE Star V2 polls for) is a follow-up.
 */
export const cancelOrder = onCall({ region: REGION }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }

  const orderId = request.data?.orderId;
  if (typeof orderId !== "string" || orderId.length === 0) {
    throw new HttpsError("invalid-argument", "orderId is required.");
  }

  const ref = db.collection(ORDERS_COLLECTION).doc(orderId);

  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      throw new HttpsError("not-found", "Order not found.");
    }
    const order = snap.data() as OrderDoc;
    if (order.ownerUid !== request.auth!.uid) {
      throw new HttpsError("permission-denied", "You do not own this order.");
    }
    if (order.status !== "QUEUED") {
      throw new HttpsError(
        "failed-precondition",
        `Cannot cancel an order in status ${order.status}; only QUEUED orders can be cancelled.`
      );
    }
    tx.update(ref, {
      status: "SKIPPED",
      statusDetail: "Cancelled by user",
      updatedAt: FieldValue.serverTimestamp(),
    });
  });

  return { ok: true };
});

/**
 * Lists the caller's own orders, most recent first. A thin wrapper over the same
 * query the web UI could run directly against Firestore (rules already scope reads
 * to request.auth.uid == ownerUid) - exposed as a callable mainly so the UI doesn't
 * need the Firestore client SDK wired up just to read a list once.
 */
export const listOrders = onCall({ region: REGION }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign in required.");
  }

  const limit = Math.min(Math.max(Number(request.data?.limit) || 100, 1), 500);

  const snap = await db
    .collection(ORDERS_COLLECTION)
    .where("ownerUid", "==", request.auth.uid)
    .orderBy("createdAt", "desc")
    .limit(limit)
    .get();

  return {
    orders: snap.docs.map((d) => ({ orderId: d.id, ...d.data() })),
  };
});
