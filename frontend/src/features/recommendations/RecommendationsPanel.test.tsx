import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { recommendationsAPI } from "./api";
import RecommendationsPanel from "./RecommendationsPanel";
import type { Recommendations } from "./types";

vi.mock("./api", () => ({ recommendationsAPI: { get: vi.fn() } }));

/** SYNTHETIC response, shaped like GET /api/recommendations. */
const BASE: Recommendations = {
  period: { from: "2026-04-01", to: "2026-06-30", months: 3, monthsWithData: 3 },
  disclaimer: "General guidance based on simple rules applied to your own records. It is not financial, tax or investment advice and does not know your full situation. You can ignore any item.",
  healthy: false,
  recommendations: [
    {
      id: "LOW_SAVINGS_RATE", severity: "HIGH", title: "Savings rate below 20%",
      explanation: "Over the last 3 months you kept 8% of recorded income.",
      suggestedAction: "Look at your largest flexible categories first.",
      evidence: [
        { metric: "Savings rate", value: 8, unit: "%", source: "/api/analytics/summary?from=2026-04-01&to=2026-06-30" },
        { metric: "Total income", value: 150000, unit: "₹", source: "/api/analytics/summary?from=2026-04-01&to=2026-06-30" },
      ],
      estimatedMonthlyImpact: 6000,
      scenarioPreset: { label: "Cut Dining by 10%", request: { horizonMonths: 6, categoryAdjustments: [{ category: "DINING", type: "PERCENTAGE", value: -10 }] } },
    },
    {
      id: "UNCATEGORISED_SHARE", severity: "INFO", title: "Many transactions have no category",
      explanation: "30% of your 100 transactions have no category.", suggestedAction: "Open Transactions and categorise them.",
      evidence: [{ metric: "Uncategorised transactions", value: 30, unit: "transactions", source: "/api/analytics/data-quality" }],
      estimatedMonthlyImpact: null, scenarioPreset: null,
    },
  ],
  notEvaluated: [{ id: "EMERGENCY_FUND", title: "Emergency fund below three months of spending", reason: "This needs your actual savings balance. FinGuard holds no account balances." }],
  thresholds: { minSavingsRatePercent: 20 },
};

function Where() {
  const loc = useLocation();
  return <div data-testid="where">{loc.pathname}:{JSON.stringify((loc.state as { preset?: { label: string } } | null)?.preset?.label ?? null)}</div>;
}

function renderPanel() {
  return render(
    <MemoryRouter initialEntries={["/dashboard"]}>
      <Routes>
        <Route path="/dashboard" element={<RecommendationsPanel />} />
        <Route path="/forecasting" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(recommendationsAPI.get).mockResolvedValue(BASE);
});

describe("RecommendationsPanel (SYNTHETIC data)", () => {
  it("shows the general-guidance notice, the period and the items in the order given", async () => {
    renderPanel();
    expect(screen.getByRole("status")).toHaveTextContent("Checking your records");
    const list = await screen.findByRole("list", { name: "Recommendations list" });
    expect(screen.getByTestId("recommendations-disclaimer")).toHaveTextContent(/not financial, tax or investment advice/);
    expect(screen.getByText("Based on 2026-04-01 to 2026-06-30")).toBeInTheDocument();
    const titles = within(list).getAllByText(/Savings rate below|Many transactions/).map((e) => e.textContent);
    expect(titles).toEqual(["Savings rate below 20%", "Many transactions have no category"]);
    expect(within(list).getByText("High")).toBeInTheDocument();
    expect(within(list).getByText("For your information")).toBeInTheDocument();
    expect(within(list).getByText("about ₹6,000 a month")).toBeInTheDocument();
  });

  it("expands to the explanation, the action and the evidence with its source", async () => {
    renderPanel();
    await screen.findByRole("list", { name: "Recommendations list" });
    const evidence = screen.getByRole("list", { name: "Evidence for Savings rate below 20%" });
    expect(within(evidence).getByText("8%")).toBeInTheDocument();
    expect(within(evidence).getByText("₹1,50,000")).toBeInTheDocument();
    expect(within(evidence).getByText("8%").getAttribute("title")).toBe("Reproduce with GET /api/analytics/summary?from=2026-04-01&to=2026-06-30");
    expect(screen.getByText(/Over the last 3 months you kept 8%/)).toBeInTheDocument();
    expect(screen.getByText(/Look at your largest flexible categories first/)).toBeInTheDocument();
  });

  it("offers Try this scenario only where there is a preset and passes it to the forecasting page", async () => {
    renderPanel();
    await screen.findByRole("list", { name: "Recommendations list" });
    expect(screen.getAllByRole("link", { name: /Try this scenario/ })).toHaveLength(1);
    fireEvent.click(screen.getByRole("link", { name: "Try this scenario: Cut Dining by 10%" }));
    expect(await screen.findByTestId("where")).toHaveTextContent('/forecasting:"Cut Dining by 10%"');
  });

  it("lists the checks that could not be made, with the reason", async () => {
    renderPanel();
    await screen.findByRole("list", { name: "Recommendations list" });
    expect(screen.getByText("What could not be checked (1)")).toBeInTheDocument();
    expect(screen.getByText(/FinGuard holds no account balances/)).toBeInTheDocument();
  });

  it("says plainly when nothing stands out", async () => {
    vi.mocked(recommendationsAPI.get).mockResolvedValue({ ...BASE, healthy: true, recommendations: [] });
    renderPanel();
    expect(await screen.findByText(/Nothing stands out/)).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: "Recommendations list" })).not.toBeInTheDocument();
  });

  it("does not call a thin history healthy", async () => {
    vi.mocked(recommendationsAPI.get).mockResolvedValue({ ...BASE, healthy: false, recommendations: [], period: { ...BASE.period, monthsWithData: 1 } });
    renderPanel();
    expect(await screen.findByText(/not enough recorded history to judge yet \(1 of the last 3 months/)).toBeInTheDocument();
    expect(screen.queryByText(/Nothing stands out/)).not.toBeInTheDocument();
  });

  it("shows an error with Retry and recovers", async () => {
    vi.mocked(recommendationsAPI.get).mockRejectedValueOnce(new Error("down")).mockResolvedValue(BASE);
    renderPanel();
    expect(await screen.findByRole("alert")).toHaveTextContent(/Could not load recommendations/);
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("list", { name: "Recommendations list" })).toBeInTheDocument();
  });
});
