package ie.atu.sw;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.concurrent.ThreadLocalRandom.current;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Arrays;
import java.util.LinkedList;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;

import javax.swing.JPanel;
import javax.swing.Timer;

public class GameView extends JPanel implements ActionListener{
	//Some constants
	private static final long serialVersionUID	= 1L;
	private static final int MODEL_WIDTH 		= 30;
	private static final int MODEL_HEIGHT 		= 20;
	private static final int SCALING_FACTOR 	= 30;
	
	private static final int MIN_TOP 			= 2;
	private static final int MIN_BOTTOM 		= 18;
	private static final int PLAYER_COLUMN 		= 15;
	private static final int TIMER_INTERVAL 	= 100;
	
	private static final byte ONE_SET 			=  1;
	private static final byte ZERO_SET 			=  0;

	/*
	 * The 30x20 game grid is implemented using a linked list of 
	 * 30 elements, where each element contains a byte[] of size 20. 
	 */
	private LinkedList<byte[]> model = new LinkedList<>();

	//These two variables are used by the cavern generator. 
	private int prevTop = MIN_TOP;
	private int prevBot = MIN_BOTTOM;
	
	//Once the timer stops, the game is over
	private Timer timer;
	private long time;
	
	private int playerRow = 11;
	private int index = MODEL_WIDTH - 1; //Start generating at the end
	private Dimension dim;
	
	//Some fonts for the UI display
	private Font font = new Font ("Dialog", Font.BOLD, 50);
	private Font over = new Font ("Dialog", Font.BOLD, 100);

	private Sprite sprite;
	private Sprite dyingSprite;
	
	private boolean auto;
	
	// Simple data collection
	private boolean collectingData = false;
	private PrintWriter trainingWriter;
	
	// Neural network controller
	private NeuralNetworkController neuralNetwork;

	public GameView(boolean auto) throws Exception{
		this.auto = auto; //Use the autopilot
		setBackground(Color.LIGHT_GRAY);
		setDoubleBuffered(true);
		
		//Creates a viewing area of 900 x 600 pixels
		dim = new Dimension(MODEL_WIDTH * SCALING_FACTOR, MODEL_HEIGHT * SCALING_FACTOR);
    	super.setPreferredSize(dim);
    	super.setMinimumSize(dim);
    	super.setMaximumSize(dim);
		
    	initModel();
    	
    	// Initialize neural network
    	neuralNetwork = new NeuralNetworkController();
    	if (auto) {
    		if (!neuralNetwork.loadNetwork()) {
    			System.out.println("No trained network found. Train first or use manual mode.");
    		}
    	}
    	
		timer = new Timer(TIMER_INTERVAL, this); //Timer calls actionPerformed() every second
		timer.start();
	}
	
	//Build our game grid
	private void initModel() {
		for (int i = 0; i < MODEL_WIDTH; i++) {
			model.add(new byte[MODEL_HEIGHT]);
		}
	}
	
	public void setSprite(Sprite s) {
		this.sprite = s;
	}
	
	public void setDyingSprite(Sprite s) {
		this.dyingSprite = s;
	}
	
	//Called every second by actionPerformed(). Paint methods are usually ugly.
	public void paintComponent(Graphics g) {
        super.paintComponent(g);
        var g2 = (Graphics2D)g;
        
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, dim.width, dim.height);
        
        int x1 = 0, y1 = 0;
        for (int x = 0; x < MODEL_WIDTH; x++) {
        	for (int y = 0; y < MODEL_HEIGHT; y++){  
    			x1 = x * SCALING_FACTOR;
        		y1 = y * SCALING_FACTOR;

        		if (model.get(x)[y] != 0) {
            		if (y == playerRow && x == PLAYER_COLUMN) {
            			timer.stop(); //Crash...
            		}
            		g2.setColor(Color.BLACK);
            		g2.fillRect(x1, y1, SCALING_FACTOR, SCALING_FACTOR);
        		}
        		
        		if (x == PLAYER_COLUMN && y == playerRow) {
        			if (timer.isRunning()) {
            			g2.drawImage(sprite.getNext(), x1, y1, null);
        			}else {
            			g2.drawImage(dyingSprite.getNext(), x1, y1, null);
        			}
        			
        		}
        	}
        }
        
        /*
         * Not pretty, but good enough for this project... The compiler will
         * tidy up and optimise all of the arithmetics with constants below.
         */
        g2.setFont(font);
        g2.setColor(Color.RED);
        g2.fillRect(1 * SCALING_FACTOR, 15 * SCALING_FACTOR, 400, 3 * SCALING_FACTOR);
        g2.setColor(Color.WHITE);
        g2.drawString("Time: " + (int)(time * (TIMER_INTERVAL/1000.0d)) + "s", 1 * SCALING_FACTOR + 10, (15 * SCALING_FACTOR) + (2 * SCALING_FACTOR));
        
        if (!timer.isRunning()) {
			g2.setFont(over);
			g2.setColor(Color.RED);
			g2.drawString("Game Over!", MODEL_WIDTH / 5 * SCALING_FACTOR, MODEL_HEIGHT / 2* SCALING_FACTOR);
        }
	}

	//Move the plane up or down
	public void move(int step) {
		playerRow += step;
		
		// Collect training data if enabled - only for UP/DOWN actions
		if (!auto && collectingData && trainingWriter != null && step != 0) {
			double[] gameState = sampleHorizon();
			StringBuilder row = new StringBuilder();
			for (int i = 0; i < gameState.length; i++) {
				row.append(gameState[i]);
				if (i < gameState.length - 1) row.append(",");
			}
			row.append(",").append((double) playerRow / MODEL_HEIGHT); // Player position
			row.append(",").append(step); // Action taken (only -1 or 1)
			trainingWriter.println(row.toString());
			trainingWriter.flush();
		}
	}
	
	
	/*
	 * ----------
	 * AUTOPILOT!
	 * ----------
	 * The following implementation randomly picks a -1, 0, 1 to control the plane. You 
	 * should plug the trained neural network in here. This method is called by the timer
	 * every TIMER_INTERVAL units of time from actionPerformed(). There are other ways of
	 * wiring your neural network into the application, but this way might be the easiest. 
	 *  
	 */
	private void autoMove() {
		if (neuralNetwork != null && neuralNetwork.isReady()) {
			double[] gameState = createGameState();
			int action = neuralNetwork.predict(gameState);
			move(action);
		} else {
			move(current().nextInt(-1, 2)); //Move -1 (up), 0 (nowhere), 1 (down)
		}
	}

	
	//Called every second by the timer 
	public void actionPerformed(ActionEvent e) {
		time++; //Update our timer
		this.repaint(); //Repaint the cavern
		
		//Update the next index to generate
		index++;
		index = (index == MODEL_WIDTH) ? 0 : index;
		
		generateNext(); //Generate the next part of the cave
		if (auto) autoMove();
		
		/*
		 * Use something like the following to extract training data.
		 * It might be a good idea to submit the double[] returned by
		 * the sample() method to an executor and then write it out 
		 * to file. You'll need to label the data too and perhaps add
		 * some more features... Finally, you do not have to sample 
		 * the data every TIMER_INTERVAL units of time. Use some modular
		 * arithmetic as shown below. Alternatively, add a key stroke 
		 * to fire an event that starts the sampling.
		 */
		if (time % 10 == 0) {
			/*
			 * double[] trainingRow = sample();
			 * System.out.println(Arrays.toString(trainingRow));
			 */
		}
	}
	
	// Simple data collection toggle
	public void toggleDataCollection() {
		if (collectingData) {
			if (trainingWriter != null) {
				trainingWriter.close();
				trainingWriter = null;
			}
			collectingData = false;
			System.out.println("Data collection stopped");
		} else {
			try {
				trainingWriter = new PrintWriter(new FileWriter("resources/training_data.csv", true));
				collectingData = true;
				System.out.println("Data collection started");
			} catch (IOException ex) {
				System.err.println("Failed to start data collection: " + ex.getMessage());
			}
		}
	}
	
	// Train neural network
	public void trainNeuralNetwork() {
		if (collectingData) {
			System.out.println("Stop data collection first (press T)");
			return;
		}
		
		new Thread(() -> {
			try {
				neuralNetwork.trainNetwork();
				System.out.println("Training completed! Press A for autopilot.");
			} catch (Exception ex) {
				System.err.println("Training failed: " + ex.getMessage());
			}
		}).start();
	}
	
	// Toggle autopilot
	public void toggleAutopilot() {
		if (auto) {
			auto = false;
			System.out.println("Manual mode");
		} else {
			if (neuralNetwork.loadNetwork()) {
				auto = true;
				System.out.println("Autopilot mode");
			} else {
				System.out.println("No trained network - train first (N key)");
			}
		}
	}
	

	
	/*
	 * Use this method to get a snapshot of the 30x20 matrix of values
	 * that make up the game grid. The grid is flatmapped into a single
	 * dimension double array... (somewhat) ready to be used by a neural 
	 * net. You can experiment around with how much of this you actually
	 * will need. The plane is always somehere in column PLAYER_COLUMN
	 * and you probably do not need any of the columns behind this. You
	 * can consider all of the columns ahead of PLAYER_COLUMN as your
	 * horizon and this value can be reduced to save space and time if
	 * needed, e.g. just look 1, 2 or 3 columns ahead. 
	 * 
	 * You may also want to track the last player movement, i.e.
	 * up, down or no change. Depending on how you design your neural
	 * network, you may also want to label the data as either okay or 
	 * dead. Alternatively, the label might be the movement (up, down
	 * or straight). 
	 *  
	 */
	public double[] sample() {
		var vector = new double[MODEL_WIDTH * MODEL_HEIGHT];
		var index = 0;
		
		for (byte[] bm : model) {
			for (byte b : bm) {
				vector[index] = b;
				index++;
			}
		}
		return vector;
	}
	
	// Sample horizon (10 columns ahead) - for training data collection
	public double[] sampleHorizon() {
		int horizonColumns = 10;
		double[] vector = new double[horizonColumns * MODEL_HEIGHT];
		int vectorIndex = 0;
		
		for (int col = PLAYER_COLUMN + 1; col < Math.min(PLAYER_COLUMN + horizonColumns + 1, MODEL_WIDTH); col++) {
			for (int row = 0; row < MODEL_HEIGHT; row++) {
				vector[vectorIndex] = (double) model.get(col)[row];
				vectorIndex++;
			}
		}
		return vector;
	}
	
	// Sample simplified horizon (3 columns ahead) - for neural network prediction
	public double[] sampleSimplifiedHorizon() {
		int horizonColumns = 3;
		double[] vector = new double[horizonColumns * MODEL_HEIGHT];
		int vectorIndex = 0;
		
		for (int col = PLAYER_COLUMN + 1; col < Math.min(PLAYER_COLUMN + horizonColumns + 1, MODEL_WIDTH); col++) {
			for (int row = 0; row < MODEL_HEIGHT; row++) {
				vector[vectorIndex] = (double) model.get(col)[row];
				vectorIndex++;
			}
		}
		return vector;
	}
	
	// Create simplified game state for neural network (61 inputs total)
	private double[] createGameState() {
		double[] horizon = sampleSimplifiedHorizon(); // 3 columns = 60 values
		double[] gameState = new double[horizon.length + 1]; // 60 + 1 = 61 total
		
		System.arraycopy(horizon, 0, gameState, 0, horizon.length);
		gameState[horizon.length] = (double) playerRow / MODEL_HEIGHT; // Normalized player position
		
		return gameState;
	}
	
	
	/*
	 * Generate the next layer of the cavern. Use the linked list to
	 * move the current head element to the tail and then randomly
	 * decide whether to increase or decrease the cavern. 
	 */
	private void generateNext() {
		var next = model.pollFirst(); 
		model.addLast(next); //Move the head to the tail
		Arrays.fill(next, ONE_SET); //Fill everything in
		
		
		//Flip a coin to determine if we could grow or shrink the cave
		var minspace = 4; //Smaller values will create a cave with smaller spaces
		prevTop += current().nextBoolean() ? 1 : -1; 
		prevBot += current().nextBoolean() ? 1 : -1;
		prevTop = max(MIN_TOP, min(prevTop, prevBot - minspace)); 		
		prevBot = min(MIN_BOTTOM, max(prevBot, prevTop + minspace));

		//Fill in the array with the carved area
		Arrays.fill(next, prevTop, prevBot, ZERO_SET);
	}

	
	/*
	 * Resets and restarts the game when the "S" key is pressed
	 */
	public void reset() {
		model.stream() 		//Zero out the grid
		     .forEach(n -> Arrays.fill(n, 0, n.length, ZERO_SET));
		playerRow = 11;		//Centre the plane
		time = 0; 			//Reset the clock
		timer.restart();	//Start the animation
	}
}