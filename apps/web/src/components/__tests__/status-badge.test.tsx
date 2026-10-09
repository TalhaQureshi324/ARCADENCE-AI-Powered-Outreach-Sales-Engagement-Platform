import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { StatusBadge } from "@/components/status-badge";

describe("StatusBadge", () => {
  it("renders its label", () => {
    render(<StatusBadge tone="ok" label="API up" />);
    expect(screen.getByText("API up")).toBeTruthy();
  });

  it("maps tones to distinct marker styles", () => {
    const { container: ok } = render(<StatusBadge tone="ok" label="x" />);
    const { container: down } = render(<StatusBadge tone="down" label="x" />);
    const { container: loading } = render(<StatusBadge tone="loading" label="x" />);
    expect(ok.querySelector("span span")?.className).toContain("bg-emerald-500");
    expect(down.querySelector("span span")?.className).toContain("bg-red-500");
    expect(loading.querySelector("span span")?.className).toContain("bg-neutral-400");
  });
});
