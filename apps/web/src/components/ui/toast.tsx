"use client";

import { createContext, useCallback, useContext, useState } from "react";

type ToastVariant = "success" | "error" | "info";

interface Toast {
  id: number;
  variant: ToastVariant;
  message: string;
}

const ToastContext = createContext<{ toast: (variant: ToastVariant, message: string) => void }>({
  toast: () => {},
});

let nextId = 1;

export function ToastProvider({ children }: { children: React.ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const toast = useCallback((variant: ToastVariant, message: string) => {
    const id = nextId++;
    setToasts((current) => [...current, { id, variant, message }]);
    setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), 5_000);
  }, []);

  const styles: Record<ToastVariant, string> = {
    success: "border-success/30 bg-success-soft text-success",
    error: "border-danger/30 bg-danger-soft text-danger",
    info: "border-primary/30 bg-primary-soft text-primary",
  };

  return (
    <ToastContext.Provider value={{ toast }}>
      {children}
      <div aria-live="polite" className="fixed right-4 bottom-4 z-[60] flex w-80 flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="status"
            className={`shadow-raised rounded-xl border px-4 py-3 text-sm ${styles[t.variant]}`}
          >
            {t.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast() {
  return useContext(ToastContext);
}
