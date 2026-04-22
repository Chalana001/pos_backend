$ErrorActionPreference = 'Stop'

$base = 'D:\My Projects\POS System\frontend\pos-frontend\src'

@'
export const QUANTITY_UNITS = {
  PCS: 'PCS',
  G: 'G',
  KG: 'KG',
};

const KG_TO_G = 1000;

const toNumber = (value) => {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : 0;
};

const trimNumber = (value, digits = 3) => {
  if (!Number.isFinite(value)) {
    return '0';
  }

  const fixed = value.toFixed(digits);
  return fixed.replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1');
};

export const isWeightItem = (item) => Boolean(item?.weightItem);

export const getDefaultQtyUnit = (item) => item?.defaultUnit || QUANTITY_UNITS.PCS;

export const getQtyInputStep = (unit) => (unit === QUANTITY_UNITS.KG ? '0.001' : '1');

export const getQtyInputMin = (unit) => (unit === QUANTITY_UNITS.KG ? '0.001' : '1');

export const getDefaultCartQuantity = (item) => {
  if (!isWeightItem(item)) {
    return 1;
  }

  return getDefaultQtyUnit(item) === QUANTITY_UNITS.G ? 100 : 1;
};

export const normalizeToBaseQuantity = (item, qty, unit = getDefaultQtyUnit(item)) => {
  const numeric = toNumber(qty);
  if (numeric <= 0) {
    return 0;
  }

  if (!isWeightItem(item)) {
    return Math.round(numeric);
  }

  return unit === QUANTITY_UNITS.KG
    ? Math.round(numeric * KG_TO_G)
    : Math.round(numeric);
};

export const toSaleFactor = (item, qty, unit = getDefaultQtyUnit(item)) => {
  const numeric = toNumber(qty);
  if (!isWeightItem(item)) {
    return numeric;
  }

  return unit === QUANTITY_UNITS.KG ? numeric : numeric / KG_TO_G;
};

export const formatQuantity = (value, unit = QUANTITY_UNITS.PCS) => {
  const numeric = toNumber(value);
  const digits = unit === QUANTITY_UNITS.KG ? 3 : 0;
  return `${trimNumber(numeric, digits)} ${unit}`;
};

export const formatQuantityValue = (value, unit = QUANTITY_UNITS.PCS) => {
  const numeric = toNumber(value);
  const digits = unit === QUANTITY_UNITS.KG ? 3 : 0;
  return trimNumber(numeric, digits);
};

export const calculateLineTotal = (item) => {
  const factor = toSaleFactor(item, item.qty, item.qtyUnit);
  let finalUnitPrice = toNumber(item.unitPrice);

  if (item.discountType === 'FIXED') {
    finalUnitPrice -= toNumber(item.discountValue);
  } else if (item.discountType === 'PERCENT') {
    finalUnitPrice -= (finalUnitPrice * toNumber(item.discountValue)) / 100;
  }

  return Math.max(0, finalUnitPrice * factor);
};

export const getPriceLabelUnit = (item) => (isWeightItem(item) ? QUANTITY_UNITS.KG : QUANTITY_UNITS.PCS);
'@ | Set-Content -Path (Join-Path $base 'utils\quantity.js')

@'
import React from "react";
import { X, Calendar, Package, AlertCircle } from "lucide-react";
import { formatCurrency } from "../../utils/formatters";
import { formatQuantity } from "../../utils/quantity";

const BatchSelectModal = ({ isOpen, onClose, onSelectBatch, item }) => {
  if (!isOpen || !item) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm animate-in fade-in duration-200">
      <div className="bg-white rounded-2xl shadow-2xl w-full max-w-lg mx-4 overflow-hidden transform transition-all scale-100 animate-in zoom-in-95 duration-200">
        <div className="px-6 py-4 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
          <div>
            <h3 className="text-lg font-bold text-slate-800">Select Batch</h3>
            <p className="text-sm text-slate-500 font-medium line-clamp-1">{item.name}</p>
          </div>
          <button
            onClick={onClose}
            className="p-2 bg-white border border-slate-200 rounded-full text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-all"
          >
            <X size={20} />
          </button>
        </div>

        <div className="p-4 space-y-3 max-h-[60vh] overflow-y-auto custom-scrollbar">
          {item.batches && item.batches.length > 0 ? (
            item.batches.map((batch) => (
              <button
                key={batch.batchId}
                onClick={() => onSelectBatch(batch)}
                className="group w-full flex items-center justify-between p-4 border border-slate-200 rounded-xl hover:border-blue-500 hover:bg-blue-50/30 hover:shadow-md transition-all text-left relative overflow-hidden"
              >
                <div className="absolute left-0 top-0 bottom-0 w-1 bg-transparent group-hover:bg-blue-500 transition-all"></div>

                <div className="flex flex-col gap-2">
                  <div className="flex items-center gap-2">
                    <span className="flex items-center gap-1.5 px-2.5 py-1 rounded-md bg-slate-100 text-slate-600 text-xs font-bold uppercase tracking-wider border border-slate-200">
                      <Package size={14} />
                      Batch #{batch.batchId}
                    </span>
                  </div>

                  {batch.expiry ? (
                    <div className="flex items-center gap-1.5 text-xs text-orange-700 font-medium ml-1">
                      <Calendar size={14} className="text-orange-500" />
                      <span>Exp: {batch.expiry}</span>
                    </div>
                  ) : (
                    <div className="flex items-center gap-1.5 text-xs text-emerald-700 font-medium ml-1">
                      <AlertCircle size={14} className="text-emerald-500" />
                      <span>No Expiry</span>
                    </div>
                  )}
                </div>

                <div className="text-right">
                  <div className="text-xl font-bold text-slate-800 group-hover:text-blue-600 transition-colors">
                    {formatCurrency(batch.price)}
                  </div>
                  <div className="text-xs font-semibold text-slate-400 mt-1">
                    <span className={batch.qty > 0 ? "text-emerald-600" : "text-red-500"}>
                      {formatQuantity(batch.displayQty ?? batch.qty, batch.displayUnit)}
                    </span>{" "}
                    Available
                  </div>
                </div>
              </button>
            ))
          ) : (
            <div className="text-center py-10 text-slate-400">
              <Package size={48} className="mx-auto mb-3 opacity-20" />
              <p>No batches found for this item.</p>
            </div>
          )}
        </div>

        <div className="px-6 py-3 bg-slate-50 border-t border-slate-100 text-center">
          <p className="text-xs text-slate-400">
            Please verify the price and batch number on the physical product.
          </p>
        </div>
      </div>
    </div>
  );
};

export default BatchSelectModal;
'@ | Set-Content -Path (Join-Path $base 'components\pos\BatchSelectModal.jsx')

@'
import React, { useState } from "react";
import { Trash2, Tag, UserPlus, Receipt, X } from "lucide-react";
import { formatCurrency } from "../../utils/formatters";
import { DISCOUNT_TYPES } from "../../utils/constants";
import {
  calculateLineTotal,
  formatQuantity,
  getPriceLabelUnit,
  getQtyInputMin,
  getQtyInputStep,
  isWeightItem,
} from "../../utils/quantity";
import Button from "../../components/common/Button";

const Cart = ({
  items,
  customer,
  onUpdateItem,
  onRemoveItem,
  onInlineDiscount,
  total,
  subTotal,
  billDiscount,
  setBillDiscount,
  onCheckout,
  loading,
  onAddCustomer,
}) => {
  const [editingIndex, setEditingIndex] = useState(null);

  const handleKeyDown = (e) => {
    if (e.key === "Enter") {
      setEditingIndex(null);
    }
  };

  return (
    <div className="flex flex-col h-full bg-white">
      <div className="p-4 border-b border-slate-100 flex items-center justify-between bg-slate-50/50">
        <div className="flex items-center gap-2">
          <div className="bg-blue-600 text-white p-2 rounded-lg">
            <Receipt size={18} />
          </div>
          <h2 className="font-bold text-slate-800">Current Order</h2>
        </div>
        <span className="bg-blue-100 text-blue-700 px-2.5 py-0.5 rounded-full text-xs font-bold">
          {items.length} Items
        </span>
      </div>

      <div className="px-4 py-3 border-b border-slate-50">
        {customer ? (
          <div className="flex items-center justify-between bg-blue-50 p-2 rounded-xl border border-blue-100">
            <div className="flex items-center gap-3">
              <div className="w-8 h-8 rounded-full bg-blue-600 flex items-center justify-center text-white text-xs font-bold">
                {customer.name.charAt(0)}
              </div>
              <div>
                <p className="text-xs font-bold text-blue-800">{customer.name}</p>
                <p className="text-[10px] text-blue-600">{customer.phone}</p>
              </div>
            </div>
            <button onClick={onAddCustomer} className="text-blue-400 hover:text-blue-600">
              <UserPlus size={16} />
            </button>
          </div>
        ) : (
          <button
            onClick={onAddCustomer}
            className="w-full flex items-center justify-center gap-2 py-2 border-2 border-dashed border-slate-200 rounded-xl text-slate-400 hover:border-blue-400 hover:text-blue-500 transition-all text-sm font-medium"
          >
            <UserPlus size={18} />
            Add Customer (F4)
          </button>
        )}
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-3 custom-scrollbar">
        {items.length === 0 ? (
          <div className="h-full flex flex-col items-center justify-center opacity-20 grayscale">
            <Receipt size={64} className="mb-4" />
            <p className="font-bold">Cart is empty</p>
          </div>
        ) : (
          items.map((item, index) => {
            const lineTotal = calculateLineTotal(item);
            const weightItem = isWeightItem(item);
            const priceLabelUnit = getPriceLabelUnit(item);

            return (
              <div key={`${item.itemId}-${item.batchId}-${index}`} className="relative group overflow-hidden bg-white border border-slate-100 rounded-xl hover:border-blue-200 transition-all">
                <div className="p-3 flex items-start gap-3">
                  <div className="flex-1 min-w-0">
                    <h4 className="text-sm font-bold text-slate-800 line-clamp-1">{item.name}</h4>
                    <div className="flex items-center gap-2 mt-1 flex-wrap">
                      <span className="text-xs font-medium text-slate-400">
                        {formatCurrency(item.unitPrice)} / {priceLabelUnit}
                      </span>
                      <span className="text-[10px] font-semibold text-emerald-600 bg-emerald-50 px-1.5 py-0.5 rounded">
                        Stock: {formatQuantity(item.stockDisplayQty, item.defaultUnit)}
                      </span>
                      {item.discountValue > 0 && (
                        <span className="text-[10px] bg-amber-100 text-amber-700 px-1.5 py-0.5 rounded font-bold">
                          -{item.discountType === DISCOUNT_TYPES.PERCENT ? `${item.discountValue}%` : formatCurrency(item.discountValue)}
                        </span>
                      )}
                    </div>

                    <div className="mt-3 flex items-center gap-2 flex-wrap">
                      <input
                        type="number"
                        min={weightItem ? getQtyInputMin(item.qtyUnit) : 1}
                        step={weightItem ? getQtyInputStep(item.qtyUnit) : 1}
                        value={item.qty}
                        onChange={(e) => onUpdateItem(index, { qty: e.target.value })}
                        className="w-24 border border-slate-200 rounded-lg px-3 py-2 text-sm font-semibold focus:outline-none focus:ring-2 focus:ring-blue-500"
                      />
                      {weightItem ? (
                        <select
                          value={item.qtyUnit}
                          onChange={(e) => onUpdateItem(index, { qtyUnit: e.target.value })}
                          className="border border-slate-200 rounded-lg px-3 py-2 text-sm font-semibold focus:outline-none focus:ring-2 focus:ring-blue-500"
                        >
                          <option value="G">G</option>
                          <option value="KG">KG</option>
                        </select>
                      ) : (
                        <span className="text-xs font-semibold text-slate-500 px-2 py-2 bg-slate-100 rounded-lg">PCS</span>
                      )}
                      <span className="ml-auto text-sm font-bold text-slate-800">{formatCurrency(lineTotal)}</span>
                    </div>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => setEditingIndex(editingIndex === index ? null : index)}
                      className={`p-2 rounded-lg transition-all ${editingIndex === index ? "bg-blue-600 text-white shadow-md" : "bg-slate-50 text-slate-400 hover:text-blue-600"}`}
                    >
                      <Tag size={16} />
                    </button>
                    <button onClick={() => onRemoveItem(index)} className="p-2 text-slate-300 hover:text-red-500 transition-all">
                      <Trash2 size={16} />
                    </button>
                  </div>
                </div>

                {editingIndex === index && (
                  <div className="bg-slate-50 border-t border-slate-100 p-3 animate-in slide-in-from-top duration-200">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-[10px] font-bold text-slate-500 uppercase tracking-wider">Item Discount</span>
                      <button onClick={() => setEditingIndex(null)} className="text-slate-400 hover:text-slate-600"><X size={14} /></button>
                    </div>
                    <div className="flex gap-2">
                      <select
                        value={item.discountType === DISCOUNT_TYPES.NONE ? DISCOUNT_TYPES.FIXED : item.discountType}
                        onChange={(e) => onInlineDiscount(index, e.target.value, item.discountValue)}
                        className="text-xs font-bold border border-slate-200 rounded-lg bg-white px-2 outline-none focus:ring-2 focus:ring-blue-500"
                      >
                        <option value={DISCOUNT_TYPES.FIXED}>Fixed (LKR)</option>
                        <option value={DISCOUNT_TYPES.PERCENT}>Percent (%)</option>
                      </select>
                      <input
                        type="number"
                        autoFocus
                        value={item.discountValue || ""}
                        onKeyDown={handleKeyDown}
                        onChange={(e) => onInlineDiscount(index, item.discountType === DISCOUNT_TYPES.NONE ? DISCOUNT_TYPES.FIXED : item.discountType, e.target.value)}
                        placeholder="0.00"
                        className="flex-1 text-sm font-bold border border-slate-200 rounded-lg bg-white px-3 py-2 outline-none focus:ring-2 focus:ring-blue-500 shadow-inner"
                      />
                    </div>
                    <p className="text-[9px] text-slate-400 mt-2 italic">Discount applies per {priceLabelUnit}</p>
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>

      <div className="p-4 bg-slate-50 border-t border-slate-200 space-y-3">
        <div className="space-y-2">
          <div className="flex justify-between text-slate-500 text-sm">
            <span>Subtotal</span>
            <span className="font-medium text-slate-700">{formatCurrency(subTotal)}</span>
          </div>
          <div className="flex justify-between items-center text-slate-500 text-sm">
            <span className="flex items-center gap-1"><Tag size={12} /> Bill Discount</span>
            <input
              type="number"
              value={billDiscount || ""}
              onChange={(e) => setBillDiscount(parseFloat(e.target.value) || 0)}
              placeholder="0.00"
              className="w-24 text-right font-bold text-slate-800 bg-white border border-slate-200 rounded px-2 py-1 focus:ring-2 focus:ring-blue-500 outline-none"
            />
          </div>
        </div>

        <div className="pt-3 border-t border-slate-200 flex justify-between items-end">
          <span className="font-bold text-slate-800">Total</span>
          <span className="text-3xl font-black text-blue-600 tracking-tighter">{formatCurrency(total)}</span>
        </div>

        <Button
          onClick={onCheckout}
          disabled={items.length === 0 || loading}
          className="w-full py-4 bg-blue-600 hover:bg-blue-700 text-white rounded-2xl font-bold text-lg shadow-lg shadow-blue-200 flex items-center justify-center gap-2 transition-all active:scale-95"
        >
          {loading ? "Processing..." : "Checkout (F9)"}
        </Button>
      </div>
    </div>
  );
};

export default Cart;
'@ | Set-Content -Path (Join-Path $base 'components\pos\Cart.jsx')
