import axios from "axios";

const BASE_URL = "http://localhost:8080/api";

const axiosInstance = axios.create({
  baseURL: BASE_URL,
});

// Attach JWT token to every request automatically
axiosInstance.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// ─── Types ────────────────────────────────────────────────────────────────────


// ─── Auth API ─────────────────────────────────────────────────────────────────

export interface LoginDTO {
  email: string;
  password: string;
}

export interface RegisterDTO {
  email: string;
  password: string;
  name?: string;
}

export interface AuthResponse {
  token: string;
}

export const authAPI = {
  login: (dto: LoginDTO) =>
    axiosInstance.post<AuthResponse>("/auth/login", dto).then((r) => r.data),

  register: (dto: RegisterDTO) =>
    axiosInstance.post<AuthResponse>("/auth/register", dto).then((r) => r.data),
};


export type TransactionType = "INCOME" | "EXPENSE";
export interface Transaction {
  id: number;
  title: string;
  amount: number;
  type: TransactionType;
  category: string;
  note?: string;
  createdAt: string;
}
export type CreateTransactionDTO = Omit<Transaction, "id" | "createdAt">;

export type InvestmentType =
  | "STOCKS"
  | "MUTUAL_FUND"
  | "FD"
  | "CRYPTO"
  | "GOLD"
  | "OTHER";
export interface Investment {
  id: number;
  name: string;
  type: InvestmentType;
  amountInvested: number;
  currentValue: number;
  notes?: string;
  createdAt: string;
}
export type CreateInvestmentDTO = Omit<Investment, "id" | "createdAt">;

export type LoanType =
  | "HOME"
  | "CAR"
  | "PERSONAL"
  | "EDUCATION"
  | "OTHER";
export interface Loan {
  id: number;
  name: string;
  type: LoanType;
  principal: number;
  emiAmount: number;
  interestRate: number;
  startDate: string;
  endDate: string;
  active: boolean;
  createdAt: string;
}
export type CreateLoanDTO = Omit<Loan, "id" | "createdAt">;

// ─── Summary helper (computed client-side from fetched data) ──────────────────

export interface DashboardSummary {
  totalIncome: number;
  totalExpenses: number;
  totalInvested: number;
  totalCurrentValue: number;
  activeLoans: number;
  totalEMI: number;
}

export function computeSummary(
  transactions: Transaction[],
  investments: Investment[],
  loans: Loan[]
): DashboardSummary {
  const totalIncome = transactions
    .filter((t) => t.type === "INCOME")
    .reduce((sum, t) => sum + t.amount, 0);

  const totalExpenses = transactions
    .filter((t) => t.type === "EXPENSE")
    .reduce((sum, t) => sum + t.amount, 0);

  const totalInvested = investments.reduce(
    (sum, i) => sum + i.amountInvested,
    0
  );
  const totalCurrentValue = investments.reduce(
    (sum, i) => sum + i.currentValue,
    0
  );

  const activeLoans = loans.filter((l) => l.active);
  const totalEMI = activeLoans.reduce((sum, l) => sum + l.emiAmount, 0);

  return {
    totalIncome,
    totalExpenses,
    totalInvested,
    totalCurrentValue,
    activeLoans: activeLoans.length,
    totalEMI,
  };
}

// ─── Transaction API ──────────────────────────────────────────────────────────

export const transactionAPI = {
  getAll: () =>
    axiosInstance.get<Transaction[]>("/transactions").then((r) => r.data),

  getById: (id: number) =>
    axiosInstance.get<Transaction>(`/transactions/${id}`).then((r) => r.data),

  create: (dto: CreateTransactionDTO) =>
    axiosInstance.post<Transaction>("/transactions", dto).then((r) => r.data),

  update: (id: number, dto: Partial<CreateTransactionDTO>) =>
    axiosInstance
      .put<Transaction>(`/transactions/${id}`, dto)
      .then((r) => r.data),

  delete: (id: number) =>
    axiosInstance.delete(`/transactions/${id}`).then((r) => r.data),
};

// ─── Investment API ───────────────────────────────────────────────────────────

export const investmentAPI = {
  getAll: () =>
    axiosInstance.get<Investment[]>("/investments").then((r) => r.data),

  getById: (id: number) =>
    axiosInstance.get<Investment>(`/investments/${id}`).then((r) => r.data),

  create: (dto: CreateInvestmentDTO) =>
    axiosInstance.post<Investment>("/investments", dto).then((r) => r.data),

  update: (id: number, dto: Partial<CreateInvestmentDTO>) =>
    axiosInstance
      .put<Investment>(`/investments/${id}`, dto)
      .then((r) => r.data),

  delete: (id: number) =>
    axiosInstance.delete(`/investments/${id}`).then((r) => r.data),
};

// ─── Loan API ─────────────────────────────────────────────────────────────────

export const loanAPI = {
  getAll: () =>
    axiosInstance.get<Loan[]>("/loans").then((r) => r.data),

  getById: (id: number) =>
    axiosInstance.get<Loan>(`/loans/${id}`).then((r) => r.data),

  create: (dto: CreateLoanDTO) =>
    axiosInstance.post<Loan>("/loans", dto).then((r) => r.data),

  update: (id: number, dto: Partial<CreateLoanDTO>) =>
    axiosInstance.put<Loan>(`/loans/${id}`, dto).then((r) => r.data),

  delete: (id: number) =>
    axiosInstance.delete(`/loans/${id}`).then((r) => r.data),
};

// ─── Upload API ───────────────────────────────────────────────────────────────
// Add these to the bottom of your existing api.ts file

export type JobStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

export interface UploadStatusResponse {
  jobId: string;
  status: JobStatus;
  bankName: string;
  originalFilename: string;
  totalRows: number;
  rowsProcessed: number;
  errorMessage: string | null;
  createdAt: string | null;
  completedAt: string | null;
}

export const uploadAPI = {
  /*
   * uploadCsv()
   * -----------
   * Sends the CSV file as multipart/form-data.
   * Returns immediately with jobId and status = PENDING.
   * Backend processes the file asynchronously in the background.
   */
  uploadCsv: (file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    return axiosInstance
      .post<UploadStatusResponse>("/upload/csv", formData, {
        headers: { "Content-Type": "multipart/form-data" },
      })
      .then((r) => r.data);
  },

  /*
   * getStatus()
   * -----------
   * Polls the status of an upload job.
   * Frontend calls this every 2 seconds until status = COMPLETED or FAILED.
   */
  getStatus: (jobId: string) =>
    axiosInstance
      .get<UploadStatusResponse>(`/upload/status/${jobId}`)
      .then((r) => r.data),
};