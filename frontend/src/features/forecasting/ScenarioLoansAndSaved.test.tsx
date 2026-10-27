import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { loanAPI } from "../loans/api";
import { forecastAPI } from "./api";
import SavedScenariosCard from "./components/SavedScenariosCard";
import ScenarioSimulatorCard from "./components/ScenarioSimulatorCard";
import {
  CASH_FLOW, CATEGORIES, LOAN, LOAN_SCENARIO, NO_INCOME_CASH_FLOW, RERUN_CHANGED, RERUN_UNCHANGED, SAVED_DETAIL_1, SAVED_DETAIL_2, SAVED_LIST, SCENARIO,
} from "./testFixtures";

vi.mock("./api", () => ({
  forecastAPI: { simulateScenario: vi.fn(), listScenarios: vi.fn(), saveScenario: vi.fn(), getScenario: vi.fn(), rerunScenario: vi.fn(), deleteScenario: vi.fn() },
}));
vi.mock("../loans/api", () => ({ loanAPI: { getAll: vi.fn() } }));

const lastRequest = () => vi.mocked(forecastAPI.simulateScenario).mock.calls.at(-1)?.[0];

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(forecastAPI.simulateScenario).mockResolvedValue(SCENARIO);
  vi.mocked(forecastAPI.listScenarios).mockResolvedValue([]);
  vi.mocked(loanAPI.getAll).mockResolvedValue([LOAN]);
});

describe("loan and one-time builder (SYNTHETIC data)", () => {
  async function renderBuilder(cashFlow = CASH_FLOW) {
    vi.useFakeTimers();
    render(<ScenarioSimulatorCard cashFlow={cashFlow} categories={CATEGORIES} horizonMonths={3} />);
    await settle();
  }
  async function settle() {
    await act(async () => { await vi.advanceTimersByTimeAsync(500); });
  }
  afterEach(() => vi.useRealTimers());

  it("sends a well-formed prepayment and shows the loan effect with interest saved", async () => {
    vi.mocked(forecastAPI.simulateScenario).mockResolvedValue(LOAN_SCENARIO);
    await renderBuilder();

    fireEvent.click(screen.getByRole("button", { name: "+ Add a loan change" }));
    fireEvent.change(screen.getByLabelText("Loan"), { target: { value: "loan-1" } });
    await settle();
    expect(lastRequest()).toEqual({ horizonMonths: 3 }); // PREPAY without an amount is not sent

    fireEvent.change(screen.getByLabelText("Prepayment amount"), { target: { value: "30000" } });
    await settle();
    expect(lastRequest()).toEqual({ horizonMonths: 3, loanAdjustments: [{ loanId: "loan-1", action: "PREPAY", amount: 30000 }] });

    const table = screen.getByRole("table", { name: "What each loan change does, before and after" });
    const row = within(table).getByRole("rowheader", { name: "Car loan" }).closest("tr") as HTMLElement;
    expect(within(row).getByText("11 \u2192 8")).toBeInTheDocument();
    expect(within(row).getByText("+\u20b92,100")).toBeInTheDocument();
    expect(screen.getByText(/not a lender statement/)).toBeInTheDocument();
  });

  it("closing a loan needs no amount", async () => {
    await renderBuilder();
    fireEvent.click(screen.getByRole("button", { name: "+ Add a loan change" }));
    fireEvent.change(screen.getByLabelText("Loan"), { target: { value: "loan-1" } });
    fireEvent.change(screen.getByLabelText("Loan action"), { target: { value: "CLOSE" } });
    await settle();
    expect(screen.queryByLabelText("Prepayment amount")).not.toBeInTheDocument();
    expect(lastRequest()).toEqual({ horizonMonths: 3, loanAdjustments: [{ loanId: "loan-1", action: "CLOSE" }] });
  });

  it("explains how to enable loan scenarios when there is no usable loan", async () => {
    vi.mocked(loanAPI.getAll).mockResolvedValue([{ ...LOAN, startDate: null }]);
    await renderBuilder();
    expect(screen.getByRole("button", { name: "+ Add a loan change" })).toBeDisabled();
    expect(screen.getByText(/active loan with a start date/)).toBeInTheDocument();
  });

  it("sends one-time items only when complete and within the horizon", async () => {
    await renderBuilder();
    fireEvent.click(screen.getByRole("button", { name: "+ Add a one-time item" }));
    fireEvent.change(screen.getByLabelText("One-time name"), { target: { value: "Laptop" } });
    await settle();
    expect(lastRequest()).toEqual({ horizonMonths: 3 }); // no amount yet

    fireEvent.change(screen.getByLabelText("One-time amount"), { target: { value: "40000" } });
    fireEvent.change(screen.getByLabelText("One-time month"), { target: { value: "2" } });
    await settle();
    expect(lastRequest()).toEqual({ horizonMonths: 3, oneTimeItems: [{ title: "Laptop", kind: "EXPENSE", month: 2, amount: 40000 }] });
    expect(within(screen.getByLabelText("One-time month")).getAllByRole("option")).toHaveLength(3);
  });

  it("cannot choose a one-time income without an income baseline", async () => {
    await renderBuilder(NO_INCOME_CASH_FLOW);
    fireEvent.click(screen.getByRole("button", { name: "+ Add a one-time item" }));
    expect(screen.getByRole("option", { name: "Income" })).toBeDisabled();
  });

  it("saves the current assumptions under a name and refreshes the saved list", async () => {
    vi.mocked(forecastAPI.saveScenario).mockResolvedValue(SAVED_DETAIL_1);
    await renderBuilder();
    const callsBefore = vi.mocked(forecastAPI.listScenarios).mock.calls.length;

    fireEvent.click(screen.getByRole("button", { name: "Save scenario" }));
    expect(screen.getByRole("alert", { name: "" })).toHaveTextContent(/Give the scenario a name/);
    expect(forecastAPI.saveScenario).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("Scenario name"), { target: { value: "Cut groceries" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save scenario" })); await vi.advanceTimersByTimeAsync(10); });
    expect(forecastAPI.saveScenario).toHaveBeenCalledWith({ horizonMonths: 3, name: "Cut groceries" });
    expect(screen.getByText("Saved \u201cCut groceries\u201d.")).toBeInTheDocument();
    expect(vi.mocked(forecastAPI.listScenarios).mock.calls.length).toBeGreaterThan(callsBefore);
  });

  it("shows the server message when saving fails", async () => {
    vi.mocked(forecastAPI.saveScenario).mockRejectedValue({ isAxiosError: true, response: { data: { message: "You can keep at most 50 saved scenarios. Delete one first." } } });
    await renderBuilder();
    fireEvent.change(screen.getByLabelText("Scenario name"), { target: { value: "One too many" } });
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save scenario" })); await vi.advanceTimersByTimeAsync(10); });
    expect(screen.getByText(/at most 50 saved scenarios/)).toBeInTheDocument();
  });
});

describe("saved scenarios", () => {
  it("shows an empty state", async () => {
    render(<SavedScenariosCard refreshKey={0} />);
    expect(await screen.findByText(/Nothing saved yet/)).toBeInTheDocument();
  });

  it("shows an error when the list cannot be loaded", async () => {
    vi.mocked(forecastAPI.listScenarios).mockRejectedValue(new Error("down"));
    render(<SavedScenariosCard refreshKey={0} />);
    expect(await screen.findByRole("alert")).toHaveTextContent(/Could not load your saved scenarios/);
  });

  it("compares up to three scenarios with the same horizon side by side", async () => {
    vi.mocked(forecastAPI.listScenarios).mockResolvedValue(SAVED_LIST);
    vi.mocked(forecastAPI.getScenario).mockImplementation((id: string) => Promise.resolve(id === "s1" ? SAVED_DETAIL_1 : SAVED_DETAIL_2));
    render(<SavedScenariosCard refreshKey={0} />);
    await screen.findByText("Cut groceries");

    fireEvent.click(screen.getByLabelText("Compare Cut groceries"));
    expect(screen.getByLabelText("Compare Half year plan")).toBeDisabled(); // different horizon
    fireEvent.click(screen.getByLabelText("Compare Prepay car loan"));

    const table = await screen.findByRole("table", { name: /Saved scenarios compared with the baseline/ });
    expect(within(table).getByRole("columnheader", { name: "Cut groceries" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "Prepay car loan" })).toBeInTheDocument();
    const net = within(table).getByRole("rowheader", { name: "Net cash flow" }).closest("tr") as HTMLElement;
    expect(within(net).getByText("\u20b960,000")).toBeInTheDocument(); // baseline
    expect(within(net).getByText("\u20b980,700")).toBeInTheDocument(); // groceries scenario
    expect(within(net).getByText("+\u20b920,700")).toBeInTheDocument();
    expect(within(table).getByRole("rowheader", { name: /Loan interest saved/ })).toBeInTheDocument();
  });

  it("re-runs a scenario: same result while the data is unchanged", async () => {
    vi.mocked(forecastAPI.listScenarios).mockResolvedValue(SAVED_LIST);
    vi.mocked(forecastAPI.rerunScenario).mockResolvedValue(RERUN_UNCHANGED);
    render(<SavedScenariosCard refreshKey={0} />);
    await screen.findByText("Cut groceries");
    fireEvent.click(screen.getByRole("button", { name: "Re-run Cut groceries" }));
    expect(await screen.findByText(/Same result as when it was saved/)).toBeInTheDocument();
    expect(forecastAPI.rerunScenario).toHaveBeenCalledWith("s1");
  });

  it("re-runs a scenario: shows the saved and the current figures when records changed", async () => {
    vi.mocked(forecastAPI.listScenarios).mockResolvedValue(SAVED_LIST);
    vi.mocked(forecastAPI.rerunScenario).mockResolvedValue(RERUN_CHANGED);
    render(<SavedScenariosCard refreshKey={0} />);
    await screen.findByText("Cut groceries");
    fireEvent.click(screen.getByRole("button", { name: "Re-run Cut groceries" }));
    expect(await screen.findByText(/Your records have changed since this was saved/)).toBeInTheDocument();
    const table = screen.getByRole("table", { name: /saved result compared with a fresh calculation/ });
    expect(within(table).getAllByRole("columnheader", { name: /when saved|now/ })).toHaveLength(4);
  });

  it("says when the forecasting method changed", async () => {
    vi.mocked(forecastAPI.listScenarios).mockResolvedValue(SAVED_LIST);
    vi.mocked(forecastAPI.rerunScenario).mockResolvedValue({ ...RERUN_CHANGED, methodVersionChanged: true, currentMethodVersion: "LEVEL_DAMPED_TREND 3.0.0" });
    render(<SavedScenariosCard refreshKey={0} />);
    await screen.findByText("Cut groceries");
    fireEvent.click(screen.getByRole("button", { name: "Re-run Cut groceries" }));
    expect(await screen.findByText(/forecasting method changed since this was saved \(now LEVEL_DAMPED_TREND 3.0.0\)/)).toBeInTheDocument();
  });

  it("deletes a scenario and reloads the list", async () => {
    vi.mocked(forecastAPI.listScenarios).mockResolvedValueOnce(SAVED_LIST).mockResolvedValue([SAVED_LIST[1], SAVED_LIST[2]]);
    vi.mocked(forecastAPI.deleteScenario).mockResolvedValue(undefined);
    render(<SavedScenariosCard refreshKey={0} />);
    await screen.findByText("Cut groceries");
    fireEvent.click(screen.getByRole("button", { name: "Delete Cut groceries" }));
    await waitFor(() => expect(screen.queryByText("Cut groceries")).not.toBeInTheDocument());
    expect(forecastAPI.deleteScenario).toHaveBeenCalledWith("s1");
  });
});

describe("scenario preset from a recommendation", () => {
  it("starts with the suggested category cut, which the user can change, and sends it to the backend", async () => {
    vi.useFakeTimers();
    try {
      render(<ScenarioSimulatorCard cashFlow={CASH_FLOW} categories={CATEGORIES} horizonMonths={6}
        preset={{ label: "Cut Dining by 10%", request: { horizonMonths: 6, categoryAdjustments: [{ category: "DINING", type: "PERCENTAGE", value: -10 }] } }} />);
      await act(async () => { await vi.advanceTimersByTimeAsync(500); });
      expect(screen.getByLabelText("Category")).toHaveValue("DINING");
      expect(screen.getByLabelText("Category change value")).toHaveValue(-10);
      expect(lastRequest()).toEqual({ horizonMonths: 6, categoryAdjustments: [{ category: "DINING", type: "PERCENTAGE", value: -10 }] });
    } finally {
      vi.useRealTimers();
    }
  });

  it("ignores a preset for a category that has no forecast", async () => {
    vi.useFakeTimers();
    try {
      render(<ScenarioSimulatorCard cashFlow={CASH_FLOW} categories={CATEGORIES} horizonMonths={3}
        preset={{ label: "x", request: { categoryAdjustments: [{ category: "NO_SUCH_CATEGORY", type: "PERCENTAGE", value: -10 }] } }} />);
      await act(async () => { await vi.advanceTimersByTimeAsync(500); });
      expect(screen.queryByLabelText("Category")).not.toBeInTheDocument();
      expect(lastRequest()).toEqual({ horizonMonths: 3 });
    } finally {
      vi.useRealTimers();
    }
  });
});
