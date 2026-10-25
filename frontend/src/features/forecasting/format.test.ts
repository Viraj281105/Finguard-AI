import { describe, expect, it } from "vitest";
import { monthLabel, shortMonth, signed, tidyMoney } from "./format";

describe("forecast formatting", () => {
  it("regroups plain amounts in server text without changing any number", () => {
    expect(tidyMoney("estimates span ₹18600.00 to ₹30000.00 (budget ₹1000000.50)")).toBe("estimates span ₹18,600 to ₹30,000 (budget ₹10,00,000.5)");
    expect(tidyMoney("₹1057.14 per day")).toBe("₹1,057.14 per day");
    expect(tidyMoney("no amounts here, 40% and 3 months")).toBe("no amounts here, 40% and 3 months");
  });

  it("labels months and leaves anything else untouched", () => {
    expect(monthLabel("2026-08")).toBe("Aug 2026");
    expect(monthLabel("2027-01")).toBe("Jan 2027");
    expect(monthLabel("not a month")).toBe("not a month");
    expect(shortMonth("2026-12")).toBe("Dec");
  });

  it("signs amounts", () => {
    const fmt = (n: number) => `₹${n}`;
    expect(signed(5, fmt)).toBe("+₹5");
    expect(signed(-5, fmt)).toBe("−₹5");
    expect(signed(0, fmt)).toBe("₹0");
  });
});
