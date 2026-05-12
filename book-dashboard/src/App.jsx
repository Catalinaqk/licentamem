import React, { useState, useEffect } from 'react';

function App() {
  const [inputText, setInputText] = useState("");
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);

  const [topics, setTopics] = useState([]);
  const [experts, setExperts] = useState([]);
  const [books, setBooks] = useState([]);

  const username = "Maia";

  useEffect(() => {
    fetch(`/api/utilizator/profil?username=${username}`)
        .then(res => res.json())
        .then(data => {
          if (data.tags) setTopics(data.tags);
          if (data.experts) setExperts(data.experts);
          if (data.books) setBooks(data.books);
        })
        .catch(err => console.error("Eroare preluare profil:", err));
  }, []);

  // Am modificat funcția ca să accepte text direct (pentru butoanele din stânga)
  const handleSendMessage = async (textToSend = inputText) => {
    if (!textToSend.trim()) return;

    const userMessage = { sender: 'user', text: textToSend };
    const newMessages = [...messages, userMessage];

    setMessages(newMessages);
    setInputText("");
    setIsLoading(true);

    try {
      const response = await fetch('/api/agent/experti-smart', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: username,
          mesaj: textToSend,
          istoric: newMessages
        })
      });

      const aiText = await response.text();
      setMessages(prev => [...prev, { sender: 'ai', text: aiText }]);
    } catch (error) {
      setMessages(prev => [...prev, { sender: 'ai', text: "❌ Eroare la conectarea cu Agentul Java." }]);
    } finally {
      setIsLoading(false);
    }
  };

  return (
      <div className="flex h-screen bg-white text-gray-800 font-sans">

        {/* SIDEBAR - Acum butoanele sunt funcționale! */}
        <div className="w-64 bg-gray-50 border-r border-gray-200 flex flex-col">
          <div className="p-6">
            <h1 className="text-xl font-bold text-gray-900">Book Reading AI</h1>
            <p className="text-xs text-gray-500 mt-1">Your personal reading companion</p>
          </div>
          <nav className="flex-1 px-4 space-y-2 mt-4">
            <div
                onClick={() => handleSendMessage("Dă-mi o sugestie rapidă, la întâmplare, ceva ce crezi că mi-ar plăcea la nebunie!")}
                className="px-4 py-3 bg-gray-100 rounded-lg cursor-pointer font-medium text-sm hover:bg-gray-200 transition-colors">
              Quick Random Suggestions
            </div>
            <div
                onClick={() => handleSendMessage("Recomandă-mi te rog 3 cărți foarte bune, apărute recent (New Releases).")}
                className="px-4 py-3 hover:bg-gray-100 rounded-lg cursor-pointer text-sm transition-colors">
              New Releases (last 3 months)
            </div>
            <div
                onClick={() => handleSendMessage("Pornind de la cărțile pe care le-am citit deja (My Books), ce mi-ai mai recomanda similar?")}
                className="px-4 py-3 hover:bg-gray-100 rounded-lg cursor-pointer text-sm transition-colors mt-4">
              My Books Analysis
            </div>
          </nav>
        </div>

        <div className="flex-1 flex flex-col h-screen">
          <div className="p-4 border-b border-gray-200 flex gap-4 bg-white shrink-0">
            <div className="flex-1 border border-gray-200 rounded-xl p-4 overflow-y-auto max-h-40">
              <h3 className="text-sm font-semibold mb-3">Selected Topics ({topics.length})</h3>
              <div className="flex flex-wrap gap-2">
                {topics.length > 0 ? topics.map((t, idx) => (
                    <span key={idx} className="px-3 py-1 bg-black text-white text-xs rounded-full">{t}</span>
                )) : <span className="text-xs text-gray-400">Nu ai selectat domenii.</span>}
              </div>
            </div>
            <div className="flex-1 border border-gray-200 rounded-xl p-4 flex flex-col overflow-y-auto max-h-40">
              <h3 className="text-sm font-semibold mb-3">Followed Experts ({experts.length})</h3>
              <div className="space-y-2 flex-1">
                {experts.length > 0 ? experts.map((e, idx) => (
                    <div key={idx} className="bg-gray-50 px-3 py-2 rounded border border-gray-100 text-sm">🧠 {e}</div>
                )) : <span className="text-xs text-gray-400">Nu urmărești niciun expert.</span>}
              </div>
            </div>
            <div className="flex-1 border border-gray-200 rounded-xl p-4 flex flex-col overflow-y-auto max-h-40">
              <h3 className="text-sm font-semibold mb-3">Read Books ({books.length})</h3>
              <div className="space-y-2 flex-1">
                {books.length > 0 ? books.map((b, idx) => (
                    <div key={idx} className="bg-gray-50 px-3 py-2 rounded border border-gray-100 text-sm">📚 {b.title}</div>
                )) : <span className="text-xs text-gray-400">Nu ai adăugat cărți.</span>}
              </div>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto p-6 flex flex-col bg-gray-50/30 gap-4">
            {messages.length === 0 ? (
                <div className="m-auto text-center text-gray-400">
                  <p className="text-gray-500 mb-2">Ask for a book recommendation to get started.</p>
                  <p className="text-sm">GraphRAG is ready to analyze your profile.</p>
                </div>
            ) : (
                messages.map((msg, idx) => (
                    <div key={idx} className={`max-w-[80%] p-4 rounded-xl ${msg.sender === 'user' ? 'bg-black text-white self-end rounded-br-none' : 'bg-white border border-gray-200 text-gray-800 self-start rounded-bl-none shadow-sm'}`}>
                      {msg.sender === 'ai' ? (
                          <div dangerouslySetInnerHTML={{ __html: msg.text.replace(/\n/g, '<br/>') }} />
                      ) : (
                          msg.text
                      )}
                    </div>
                ))
            )}
            {isLoading && (
                <div className="self-start bg-white border border-gray-200 p-4 rounded-xl rounded-bl-none shadow-sm text-gray-500 animate-pulse">
                  Mentorul analizează și caută... 🧠
                </div>
            )}
          </div>

          <div className="p-4 border-t border-gray-200 bg-white shrink-0">
            <div className="flex gap-2 max-w-5xl mx-auto">
              <input
                  type="text"
                  className="flex-1 bg-gray-50 border border-gray-200 rounded-xl px-4 py-3 focus:outline-none focus:border-gray-400 focus:ring-1 focus:ring-gray-400"
                  placeholder="Vorbește cu mentorul tău..."
                  value={inputText}
                  onChange={(e) => setInputText(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSendMessage(inputText)}
              />
              <button
                  onClick={() => handleSendMessage(inputText)}
                  disabled={isLoading}
                  className={`px-8 py-3 rounded-xl font-medium transition-colors ${isLoading ? 'bg-gray-400 text-gray-200 cursor-not-allowed' : 'bg-black text-white hover:bg-gray-800'}`}
              >
                Send
              </button>
            </div>
          </div>
        </div>
      </div>
  );
}

export default App;