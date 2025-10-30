from flask import Flask, request
import chess
from stockfish import Stockfish
import os

app = Flask(__name__)

class ChessGame:
    def __init__(self):
        self.board = None
        self.user_color = None
        self.game_started = False
        self.waiting_for_color = False

        stockfish_path = self._find_stockfish()
        if not stockfish_path:
            raise RuntimeError("Stockfish not found. Install with: apt-get install -y stockfish")

        self.engine = Stockfish(path=stockfish_path, depth=20, parameters={
            "Threads": 4,
            "Hash": 2048,
            "Minimum Thinking Time": 100,
            "Skill Level": 20,
            "UCI_LimitStrength": False
        })

    def _find_stockfish(self):
        possible_paths = [
            "/usr/games/stockfish",
            "/usr/bin/stockfish",
            "/usr/local/bin/stockfish",
            "stockfish"
        ]
        for path in possible_paths:
            if os.path.isfile(path) or os.access(path, os.X_OK):
                return path
        import shutil
        return shutil.which("stockfish")

    def start_game(self):
        self.board = chess.Board()
        self.game_started = False
        self.waiting_for_color = True
        return "Game started. Choose engine color: black or white?"

    def set_color(self, color_input):
        color_input = color_input.strip().lower()
        if color_input not in ['black', 'white']:
            return "Invalid color. Choose 'black' or 'white'."

        engine_color = chess.WHITE if color_input == 'white' else chess.BLACK
        self.user_color = not engine_color
        self.game_started = True
        self.waiting_for_color = False

        if engine_color == chess.WHITE:
            engine_move = self._get_engine_move()
            if engine_move:
                return f"You are white. First move: {engine_move}"
            return "Engine error"
        else:
            return "Engine is black. You are white. Make your move."

    def _get_engine_move(self):
        self.engine.set_fen_position(self.board.fen())
        best_move = self.engine.get_best_move_time(2000)
        if best_move:
            move = chess.Move.from_uci(best_move)
            if move in self.board.legal_moves:
                self.board.push(move)
                return best_move
        return None

    def make_move(self, move_input):
        move_input = move_input.strip().lower()
        try:
            move = chess.Move.from_uci(move_input)
        except:
            return "Illegal move"
        if move not in self.board.legal_moves:
            return "Illegal move"

        self.board.push(move)

        if self.board.is_checkmate():
            return "Checkmate, you win!"
        if self.board.is_stalemate() or self.board.is_insufficient_material():
            return "Draw"

        engine_move = self._get_engine_move()
        if not engine_move:
            return "Engine error"

        if self.board.is_checkmate():
            return f"{engine_move}\nCheckmate, I win!"
        if self.board.is_stalemate() or self.board.is_insufficient_material():
            return f"{engine_move}\nDraw"

        return engine_move

game = ChessGame()

@app.route('/start', methods=['GET', 'POST'])
def start():
    result = game.start_game()
    return result, 200, {'Content-Type': 'text/plain'}

@app.route('/move', methods=['POST'])
def move():
    move_input = request.get_data(as_text=True).strip()

    if game.waiting_for_color:
        result = game.set_color(move_input)
        return result, 200, {'Content-Type': 'text/plain'}

    if not game.game_started:
        return "No game in progress. Start a game with /start", 200, {'Content-Type': 'text/plain'}

    result = game.make_move(move_input)
    return result, 200, {'Content-Type': 'text/plain'}

def run_server():
    port = int(os.environ.get('PORT', 5000))

    print("="*60)
    print("BRUTAL CHESS ENGINE - HTTP API")
    print("="*60)
    print("\nEndpoints:")
    print("  POST /start  - Start a new game")
    print("  POST /move   - Make a move (UCI format: e2e4)")
    print("\nEngine Settings:")
    print("  Depth: 20 (Maximum)")
    print("  Skill Level: 20 (Brutal)")
    print("  Threads: 4")
    print("  Hash: 2048 MB")
    print(f"\nLocal Server: http://0.0.0.0:{port}")
    print("="*60)

    try:
        from pyngrok import ngrok
        import time
        ngrok_token = os.environ.get('NGROK_AUTHTOKEN')
        if ngrok_token:
            ngrok.set_auth_token(ngrok_token)
        time.sleep(1)
        public_url = ngrok.connect(port)
        url_string = str(public_url.public_url) if hasattr(public_url, 'public_url') else str(public_url)
        print("\n" + "="*60)
        print("PUBLIC URL - ACCESS FROM ANYWHERE:")
        print("="*60)
        print(f"\n{url_string}")
        print("\nExample commands:")
        print(f"  curl -X POST {url_string}/start")
        print(f"  curl -X POST {url_string}/move -d \"white\"")
        print(f"  curl -X POST {url_string}/move -d \"e2e4\"")
        print("\n" + "="*60 + "\n")
        
        telegram_token = os.environ.get('TELEGRAM_BOT_TOKEN')
        if telegram_token:
            try:
                import asyncio
                from telegram import Bot
                async def send_notification():
                    bot = Bot(telegram_token)
                    message = f"🎮 Chess Engine is Live!\n\nNgrok URL: {url_string}\n\nExample commands:\ncurl -X POST {url_string}/start\ncurl -X POST {url_string}/move -d \"white\"\ncurl -X POST {url_string}/move -d \"e2e4\""
                    await bot.send_message(chat_id=6173586090, text=message)
                    print("✓ Telegram notification sent!")
                asyncio.run(send_notification())
            except Exception as telegram_error:
                print(f"Telegram notification failed: {telegram_error}")
    except Exception as e:
        print(f"\nngrok error: {e}")
        print("Make sure you've set your ngrok authtoken!")

    app.run(host='0.0.0.0', port=port, debug=False)

if __name__ == '__main__':
    run_server()
