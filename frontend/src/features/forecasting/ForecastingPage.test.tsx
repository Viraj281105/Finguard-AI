import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { forecastAPI } from "./api";
import ForecastPreviewCard from "./components/ForecastPreviewCard";
import ForecastingPage from "./ForecastingPage";
import {
  BACKTEST, BUDGET, CASH_FLOW, CATEGORIES, LIMITED_CASH_FLOW, NO_INCOME_CASH_FLOW, SAVINGS, SCENARIO, UNAVAILABLE_CASH_FLOW,
} from "./testFixtures";

vi.mock("./api", () => ({
  forecastAPI: {
    getCashFlow: vi.fn(),
    getCategories: vi.fn(),
    getBudgetOutlook: vi.fn(),
    getSavings: vi.fn(),
    simulateScenario: vi.fn(),
    getBacktest: vi.fn(),
    listScenarios: vi.fn(),
    saveScenario: vi.fn(),
    getScenario: vi.fn(),
    rerunScenario: vi.fn(),
    deleteScenario: vi.fn(),
  },
}));

vi.mock("../loans/api", () => ({ loanAPI: { getAll: vi.fn().mockResolvedValue([]) } }));
vi.mock("../auth/api", () => ({ authAPI: { logout: vi.fn().mockResolvedValue({}) } }));

function renderPage() {
  render(
    <MemoryRouter>
      <ForecastingPage />
    </MemoryRouter>,
  );
}

async function openTab(name: string) {
  await userEvent.click(screen.getByRole("tab", { name }));
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(forecastAPI.getCashFlow).mockResolvedValue(CASH_FLOW);
  vi.mocked(forecastAPI.getCategories).mockResolvedValue(CATEGORIES);
  vi.mocked(forecastAPI.getBudgetOutlook).mockResolvedValue(BUDGET);
  vi.mocked(forecastAPI.getSavings).mockResolvedValue(SAVINGS);
  vi.mocked(forecastAPI.getBacktest).mockResolvedValue(BACKTEST);
  vi.mocked(forecastAPI.simulateScenario).mockResolvedValue(SCENARIO);
  vi.mocked(forecastAPI.listScenarios).mockResolvedValue([]);
});

describe("cash-flow overview", () => {
  it("shows a loading state, then the evidence behind the forecast", async () => {
    renderPage();
    expect(screen.getByText(/Loading the cash-flow forecast/)).toBeInTheDocument();

    expect(await screen.findByTestId("reliability-badge")).toHaveTextContent("Enough history");
    expect(screen.getByText(/^Based on/)).toHaveTextContent("Based on 6 complete months (Jan 2026 – Jun 2026), 36 transactions");
    expect(screen.getAllByText(/no machine learning/i).length).toBeGreaterThan(0);
  });

  it("separates actual months, the month so far and projected months, in text and in the chart labels", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");

    expect(screen.getByRole("img", { name: /^Jun 2026, actual/ })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /^Jul 2026, so far \(incomplete month\)/ })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /^Aug 2026, projected/ })).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Chart legend" })).toHaveTextContent("Month so far (not used to forecast)");
    expect(screen.getByRole("list", { name: "Chart legend" })).toHaveTextContent("Projected");
  });

  it("draws an uncertainty range only for months where the backend supplied one", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");

    expect(screen.getByRole("img", { name: /^Aug 2026, projected.*spending range ₹28,000 to ₹32,500/ })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: /^Sep 2026, projected/ }).getAttribute("aria-label")).not.toContain("range");
  });

  it("shows the projected totals computed by the server", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");

    const totals = screen.getByRole("region", { name: "Projected totals" });
    expect(within(totals).getByText("₹1,50,000")).toBeInTheDocument();
    expect(within(totals).getByText("₹90,000")).toBeInTheDocument();
    expect(within(totals).getByText("+₹60,000")).toBeInTheDocument();
    expect(within(totals).getByText("40.0%")).toBeInTheDocument();
    expect(within(totals).getByText(/not a bank balance/)).toBeInTheDocument();
  });

  it("explains the method and assumptions on request and lists the recurring payments behind the forecast", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");

    expect(screen.getByText(/Seasonality is not modelled/)).toBeInTheDocument();
    expect(screen.getByText(/descriptive, not a calibrated probability/)).toBeInTheDocument();
    const recurring = screen.getByRole("region", { name: "Recurring payments in the forecast" });
    expect(within(recurring).getByText("Netflix subscription")).toBeInTheDocument();
    expect(recurring).toHaveTextContent("not added a second time");
  });

  it("asks for a new forecast when the horizon changes", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");

    await userEvent.click(screen.getByRole("button", { name: "6 months" }));

    await waitFor(() => expect(forecastAPI.getCashFlow).toHaveBeenLastCalledWith(6));
    expect(forecastAPI.getCategories).toHaveBeenLastCalledWith(6);
    expect(forecastAPI.getSavings).toHaveBeenLastCalledWith(6);
    expect(screen.getByRole("button", { name: "6 months" })).toHaveAttribute("aria-pressed", "true");
  });

  it("refuses to project when the history is too short, and invents no numbers", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockResolvedValue(UNAVAILABLE_CASH_FLOW);
    renderPage();

    expect(await screen.findByText("Not enough history to project")).toBeInTheDocument();
    expect(screen.getByTestId("reliability-badge")).toHaveTextContent("Not available");
    expect(screen.getByRole("link", { name: "Import a bank statement" })).toHaveAttribute("href", "/dashboard");
    expect(screen.getByText(/only 2 found/)).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Projected totals" })).not.toBeInTheDocument();
    expect(screen.queryByRole("img", { name: /projected/i })).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: /^Jun 2026, actual/ })).toBeInTheDocument(); // the facts are still shown
  });

  it("shows limited reliability with the data-quality warning that caused it", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockResolvedValue(LIMITED_CASH_FLOW);
    renderPage();

    expect(await screen.findByTestId("reliability-badge")).toHaveTextContent("Limited history");
    expect(screen.getByRole("list", { name: "Data-quality warnings" })).toHaveTextContent("treated as unknown, not as zero");
  });

  it("says 'Not projected' instead of zero when no income was observed", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockResolvedValue(NO_INCOME_CASH_FLOW);
    renderPage();
    await screen.findByTestId("reliability-badge");

    const totals = screen.getByRole("region", { name: "Projected totals" });
    expect(within(totals).getAllByText("Not projected")).toHaveLength(3);
    expect(within(totals).getByText(/No regular income found/)).toBeInTheDocument();
    expect(within(totals).queryByText("₹0")).not.toBeInTheDocument();
    expect(within(totals).getByText("₹90,000")).toBeInTheDocument(); // spending is still projected
  });

  it("keeps a failing section from blanking the page, and can retry", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockRejectedValueOnce(new Error("boom")).mockResolvedValue(CASH_FLOW);
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load the cash-flow forecast");
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByTestId("reliability-badge")).toBeInTheDocument();
    expect(forecastAPI.getCashFlow).toHaveBeenCalledTimes(2);
  });
});

describe("category spending", () => {
  it("lists projections with tested trends, evidence and the categories left out", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Spending by category");

    const table = await screen.findByRole("table", { name: "Projected monthly spending by category" });
    expect(within(table).getByRole("rowheader", { name: /Housing/ })).toHaveTextContent("includes recurring ₹20,000/mo already in history");
    const dining = within(table).getByRole("rowheader", { name: /Dining/ }).closest("tr") as HTMLElement;
    expect(within(dining).getByText("Rising +8%/mo")).toBeInTheDocument();
    expect(within(dining).getByText("₹6,576")).toBeInTheDocument();
    expect(within(dining).getByText("₹6,000 – ₹7,100")).toBeInTheDocument();
    expect(within(dining).getByText(/Unusual month\(s\) limited: Mar 2026/)).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Rising trend (statistically tested, at least 3% a month): Dining");

    await userEvent.click(screen.getByText(/1 category without a projection/));
    expect(screen.getByText(/Spending in only 1 of 6 month\(s\)/)).toBeInTheDocument();
  });
});

describe("budget outlook", () => {
  it("labels facts, ranges and missing evidence differently", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Budget outlook");

    expect(await screen.findByText(/Budget outlook: Jul 2026/)).toBeInTheDocument();
    expect(screen.getByText("At risk")).toBeInTheDocument();
    expect(screen.getByText("₹18,600 – ₹30,000")).toBeInTheDocument();
    expect(screen.getByText(/The estimates span ₹18,600 to ₹30,000/)).toBeInTheDocument(); // regrouped for reading

    expect(screen.getByText("Already over budget")).toBeInTheDocument();
    expect(screen.getByText("At least ₹6,000")).toBeInTheDocument(); // an exceeded budget is a fact, not "no data"
    expect(screen.getByText(/already above the budget/)).toBeInTheDocument();

    // not enough evidence: no number, the reason instead
    const dining = screen.getByRole("heading", { name: "Dining" }).closest("li") as HTMLElement;
    expect(within(dining).getByText("Not enough data", { selector: "span" })).toBeInTheDocument();
    expect(within(dining).getByText(/only 2 day\(s\) of data recorded/)).toBeInTheDocument();
    expect(within(dining).queryByText(/Estimated overrun/)).not.toBeInTheDocument();
  });

  it("explains how an assessment was reached", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Budget outlook");

    await userEvent.click((await screen.findAllByText("Why this assessment"))[0]);
    expect(screen.getByText(/the median of the last 6 complete months/)).toBeVisible();
  });

  it("prompts to set a budget when there are none", async () => {
    vi.mocked(forecastAPI.getBudgetOutlook).mockResolvedValue({ ...BUDGET, overall: null, categories: [], warnings: ["No budgets are set for 2026-07."] });
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Budget outlook");

    expect(await screen.findByText(/Set a monthly budget in Budgets & Goals/)).toBeInTheDocument();
    expect(screen.getByText("No budgets are set for 2026-07.")).toBeInTheDocument();
  });
});

describe("savings outlook", () => {
  it("keeps projected surplus, recorded goal contributions and bank balances apart", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Savings outlook");

    const surplus = await screen.findByRole("region", { name: "Projected surplus" });
    expect(surplus).toHaveTextContent("+₹60,000");
    expect(surplus).toHaveTextContent("not money in an account");
    const goals = screen.getByRole("region", { name: "Recorded goal contributions" });
    expect(goals).toHaveTextContent("₹6,000");
    expect(goals).toHaveTextContent("not verified bank deposits");
    const bank = screen.getByRole("region", { name: "Verified bank balance" });
    expect(bank).toHaveTextContent("Not available");
    expect(bank).toHaveTextContent("does not hold or verify account balances");
  });

  it("compares goals with the projected surplus without double-counting it", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Savings outlook");

    expect(await screen.findByText(/₹6,000 recorded of ₹60,000/)).toBeInTheDocument();
    expect(screen.getByText(/Needs about ₹10,800 a month/)).toBeInTheDocument();
    expect(screen.getByText(/If the entire projected surplus went to this goal alone: about 3 months \(Oct 2026\)/)).toBeInTheDocument();
    expect(screen.getByText(/All dated goals together need about ₹10,800 a month. That fits/)).toBeInTheDocument();
    expect(screen.getByText(/not a bank balance and not verified savings/)).toBeInTheDocument();
  });

  it("shows nothing as a surplus when it cannot be projected", async () => {
    vi.mocked(forecastAPI.getSavings).mockResolvedValue({
      ...SAVINGS, reliability: "UNAVAILABLE", incomeAvailable: false, averageMonthlyNet: null, projectedSavingsRatePercent: null, points: [], goals: [],
      accounting: { ...SAVINGS.accounting, projectedSurplusOverHorizon: null, totalRequiredGoalPace: null, goalsOvercommitted: null },
    });
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Savings outlook");

    const surplus = await screen.findByRole("region", { name: "Projected surplus" });
    expect(surplus).toHaveTextContent("Not available");
    expect(surplus).not.toHaveTextContent("₹0");
    expect(screen.queryByText(/Projected cumulative surplus/)).not.toBeInTheDocument();
    expect(screen.getByText(/No active goals/)).toBeInTheDocument();
  });

  it("reports a failing savings request without hiding the other tabs", async () => {
    vi.mocked(forecastAPI.getSavings).mockRejectedValue(new Error("down"));
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("Savings outlook");

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not load the savings outlook");
    await openTab("Cash flow");
    expect(await screen.findByTestId("reliability-badge")).toBeInTheDocument();
  });
});

describe("evaluation", () => {
  it("shows sample sizes and refuses to call a handful of forecasts conclusive", async () => {
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("How reliable is this?");

    expect(await screen.findByText(/Evaluated on your own recorded months only \(6 usable\)/)).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Spending backtest by horizon" });
    expect(within(table).getByRole("rowheader", { name: "1" })).toBeInTheDocument();
    expect(within(table).getByText("3")).toBeInTheDocument(); // forecasts scored
    expect(within(table).getByText("WAPE 4.1%")).toBeInTheDocument();
    expect(within(table).getByText("Inconclusive: too few forecasts to judge")).toBeInTheDocument();
    expect(screen.getByText(/synthetic fixtures are evaluated separately/)).toBeInTheDocument();
    expect(screen.getByText("No forecast could be scored for this series.")).toBeInTheDocument();
  });

  it("says plainly when there is not enough history to score anything", async () => {
    vi.mocked(forecastAPI.getBacktest).mockResolvedValue({ ...BACKTEST, status: "INSUFFICIENT_DATA", series: [], usableMonths: 2 });
    renderPage();
    await screen.findByTestId("reliability-badge");
    await openTab("How reliable is this?");

    expect(await screen.findByText(/Not enough history to score even one forecast/)).toBeInTheDocument();
  });
});

describe("dashboard preview", () => {
  const renderPreview = () => render(<MemoryRouter><ForecastPreviewCard /></MemoryRouter>);

  it("shows next month's projection with its basis", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockResolvedValue(CASH_FLOW);
    renderPreview();

    const card = await screen.findByRole("region", { name: "Next month projection" });
    expect(card).toHaveTextContent("Aug 2026");
    expect(card).toHaveTextContent("based on 6 recorded months");
    expect(card).toHaveTextContent("not a promise");
    expect(card).toHaveTextContent("₹50,000");
    expect(card).toHaveTextContent("+₹20,000");
    expect(forecastAPI.getCashFlow).toHaveBeenCalledWith(1);
  });

  it("renders nothing without a forecast", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockResolvedValue(UNAVAILABLE_CASH_FLOW);
    const { container } = renderPreview();
    await waitFor(() => expect(forecastAPI.getCashFlow).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when the request fails", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockRejectedValue(new Error("down"));
    const { container } = renderPreview();
    await waitFor(() => expect(forecastAPI.getCashFlow).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it("does not show a zero income when none is projected", async () => {
    vi.mocked(forecastAPI.getCashFlow).mockResolvedValue(NO_INCOME_CASH_FLOW);
    renderPreview();

    const card = await screen.findByRole("region", { name: "Next month projection" });
    expect(card).toHaveTextContent("Not projected");
    expect(card).toHaveTextContent("limited history");
  });
});
