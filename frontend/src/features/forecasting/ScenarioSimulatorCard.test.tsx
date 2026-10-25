import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { AxiosError } from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loanAPI } from "../loans/api";
import { forecastAPI } from "./api";
import ScenarioSimulatorCard from "./components/ScenarioSimulatorCard";
import { CASH_FLOW, CATEGORIES, NO_INCOME_CASH_FLOW, SCENARIO, SCENARIO_CHANGED, UNAVAILABLE_CASH_FLOW } from "./testFixtures";
import type { CashFlowForecast, ScenarioResult } from "./types";

vi.mock("./api", () => ({
  forecastAPI: { simulateScenario: vi.fn(), listScenarios: vi.fn(), saveScenario: vi.fn(), getScenario: vi.fn(), rerunScenario: vi.fn(), deleteScenario: vi.fn() },
}));
vi.mock("../loans/api", () => ({ loanAPI: { getAll: vi.fn() } }));

const DEBOUNCE = 450;

function renderCard(cashFlow: CashFlowForecast = CASH_FLOW) {
  return render(<ScenarioSimulatorCard cashFlow={cashFlow} categories={CATEGORIES} horizonMonths={3} />);
}

/** Lets the debounce elapse and the mocked request settle. */
async function settle() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(DEBOUNCE + 50);
  });
}

const lastRequest = () => vi.mocked(forecastAPI.simulateScenario).mock.calls.at(-1)?.[0];

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  vi.mocked(forecastAPI.simulateScenario).mockResolvedValue(SCENARIO);
  vi.mocked(forecastAPI.listScenarios).mockResolvedValue([]);
  vi.mocked(loanAPI.getAll).mockResolvedValue([]);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("ScenarioSimulatorCard", () => {
  it("starts from the baseline and says the scenario is hypothetical", async () => {
    renderCard();
    await settle();

    expect(lastRequest()).toEqual({ horizonMonths: 3 });
    expect(screen.getByText("Hypothetical: changes no real records")).toBeInTheDocument();
    const results = screen.getByLabelText("Scenario results");
    expect(within(results).getByText(/change nothing in your transactions, budgets or goals/)).toBeInTheDocument();
    expect(within(results).getByText(/No uncertainty band is given for scenario figures/)).toBeInTheDocument();
    expect(within(results).getByText("No adjustments chosen: the scenario equals the baseline.")).toBeInTheDocument();
  });

  it("recalculates on the backend after an income change and shows baseline, scenario and change", async () => {
    vi.mocked(forecastAPI.simulateScenario).mockResolvedValueOnce(SCENARIO).mockResolvedValue(SCENARIO_CHANGED);
    renderCard();
    await settle();

    fireEvent.change(screen.getByLabelText("Income change type"), { target: { value: "PERCENTAGE" } });
    fireEvent.change(screen.getByLabelText("Income change value"), { target: { value: "10" } });
    expect(forecastAPI.simulateScenario).toHaveBeenCalledTimes(1); // debounced: nothing sent yet
    await settle();

    expect(lastRequest()).toEqual({ horizonMonths: 3, incomeAdjustment: { type: "PERCENTAGE", value: 10 } });
    const table = screen.getByRole("table", { name: "Baseline compared with the scenario over the horizon" });
    const net = within(table).getByRole("rowheader", { name: "Net cash flow" }).closest("tr") as HTMLElement;
    expect(within(net).getByText("₹60,000")).toBeInTheDocument();
    expect(within(net).getByText("₹80,700")).toBeInTheDocument();
    expect(within(net).getByText("+₹20,700")).toBeInTheDocument();
    expect(screen.getByText(/Groceries/)).toBeInTheDocument();
    expect(screen.getByText("+8.9 pts")).toBeInTheDocument();
  });

  it("sends only well-formed category changes, new expenses and stopped recurring payments", async () => {
    renderCard();
    await settle();

    fireEvent.click(screen.getByRole("button", { name: "+ Add a category change" }));
    fireEvent.change(screen.getByLabelText("Category"), { target: { value: "DINING" } });
    await settle();
    expect(lastRequest()).toEqual({ horizonMonths: 3 }); // no value yet: not sent

    fireEvent.change(screen.getByLabelText("Category change value"), { target: { value: "-20" } });
    fireEvent.click(screen.getByRole("button", { name: "+ Add an expense" }));
    fireEvent.change(screen.getByLabelText("New expense name"), { target: { value: "Gym" } });
    fireEvent.change(screen.getByLabelText("New expense amount per month"), { target: { value: "1500" } });
    fireEvent.click(screen.getByLabelText(/Netflix subscription/));
    await settle();

    expect(lastRequest()).toEqual({
      horizonMonths: 3,
      categoryAdjustments: [{ category: "DINING", type: "PERCENTAGE", value: -20 }],
      addedRecurringExpenses: [{ title: "Gym", monthlyAmount: 1500 }],
      removedRecurringExpenseIds: ["rec-1"],
    });
  });

  it("resets every assumption", async () => {
    renderCard();
    await settle();
    fireEvent.change(screen.getByLabelText("Income change type"), { target: { value: "FIXED_AMOUNT" } });
    fireEvent.change(screen.getByLabelText("Income change value"), { target: { value: "-5000" } });
    await settle();
    expect(lastRequest()).toEqual({ horizonMonths: 3, incomeAdjustment: { type: "FIXED_AMOUNT", value: -5000 } });

    fireEvent.click(screen.getByRole("button", { name: "Reset all assumptions" }));
    await settle();

    expect(lastRequest()).toEqual({ horizonMonths: 3 });
    expect(screen.getByLabelText("Income change type")).toHaveValue("NONE");
  });

  it("shows the server's validation message when a scenario is rejected", async () => {
    const rejection = new AxiosError("bad", "400", undefined, undefined, {
      status: 400, statusText: "Bad Request", headers: {}, config: {} as never,
      data: { status: 400, message: "Percentage change for income must be between -100 and 500." },
    });
    vi.mocked(forecastAPI.simulateScenario).mockRejectedValue(rejection);
    renderCard();
    await settle();

    expect(screen.getByRole("alert")).toHaveTextContent("Percentage change for income must be between -100 and 500.");
  });

  it("ignores a slow, outdated response that arrives after a newer one", async () => {
    let releaseOld: (r: ScenarioResult) => void = () => undefined;
    vi.mocked(forecastAPI.simulateScenario)
      .mockImplementationOnce(() => new Promise<ScenarioResult>((resolve) => { releaseOld = resolve; }))
      .mockResolvedValue(SCENARIO_CHANGED);
    renderCard();
    await settle();                                   // request 1 is still pending

    fireEvent.change(screen.getByLabelText("Income change type"), { target: { value: "PERCENTAGE" } });
    fireEvent.change(screen.getByLabelText("Income change value"), { target: { value: "10" } });
    await settle();                                   // request 2 resolves with the changed scenario
    expect(screen.getByText("+₹20,700")).toBeInTheDocument();

    await act(async () => releaseOld(SCENARIO));      // the old answer finally arrives
    expect(screen.getByText("+₹20,700")).toBeInTheDocument();
  });

  it("explains that no scenario can be built without a baseline", async () => {
    vi.mocked(forecastAPI.simulateScenario).mockResolvedValue({
      ...SCENARIO, available: false, unavailableReason: "At least 3 complete months are needed.", baseline: null, scenario: null, delta: null, months: [],
    });
    renderCard(UNAVAILABLE_CASH_FLOW);
    await settle();

    expect(screen.getByText(/there is not enough history for one yet/)).toBeInTheDocument();
    expect(screen.queryByLabelText("Income change type")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("No scenario can be calculated: At least 3 complete months are needed.");
  });

  it("does not offer income changes when no income was observed", async () => {
    renderCard(NO_INCOME_CASH_FLOW);
    await settle();

    expect(screen.getByLabelText("Income change type")).toBeDisabled();
    expect(screen.getByText(/cannot be adjusted/)).toBeInTheDocument();
  });
});
