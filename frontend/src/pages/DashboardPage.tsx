import { useNavigate } from 'react-router-dom'

export default function DashboardPage() {
  const navigate = useNavigate()
  const user = JSON.parse(localStorage.getItem('user') || '{}')

  const handleLogout = () => {
    localStorage.removeItem('accessToken')
    localStorage.removeItem('user')
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-gray-950 text-white">
      <nav className="bg-gray-900 border-b border-gray-800 px-6 py-4 flex justify-between items-center">
        <h1 className="text-xl font-bold text-emerald-400">FinGuard AI 🛡️</h1>
        <div className="flex items-center gap-4">
          <span className="text-gray-300 text-sm">Welcome, {user.name}</span>
          <button
            onClick={handleLogout}
            className="bg-gray-800 hover:bg-gray-700 text-sm px-4 py-2 rounded-lg transition-colors"
          >
            Logout
          </button>
        </div>
      </nav>

      <main className="max-w-4xl mx-auto px-6 py-12">
        <h2 className="text-3xl font-bold mb-2">Dashboard</h2>
        <p className="text-gray-400 mb-8">Your financial overview coming soon.</p>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          {['Transactions', 'Investments', 'Risk Score'].map(card => (
            <div key={card} className="bg-gray-900 border border-gray-800 rounded-2xl p-6">
              <h3 className="text-gray-400 text-sm mb-2">{card}</h3>
              <p className="text-2xl font-bold text-emerald-400">—</p>
            </div>
          ))}
        </div>
      </main>
    </div>
  )
}