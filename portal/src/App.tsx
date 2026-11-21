import './App.css'
import {HashRouter, Link, Route, Routes} from 'react-router-dom'

import {Home} from './views/Home'
import {Settings} from './views/Settings'
import {InfoPage} from "./views/MainView.tsx";

function App() {

    return (
        <HashRouter>
            <nav>
                <Link to="/">Home</Link>
                {' | '}
                <Link to="/settings">Settings</Link>
                {' | '}
                <Link to="/main">MainView</Link>
            </nav>
            <Routes>
                <Route path="/" element={<Home/>}/>
                <Route path="/settings" element={<Settings/>}/>
                <Route path="/main" element={<InfoPage/>}/>
            </Routes>
        </HashRouter>
    )
}

export default App
