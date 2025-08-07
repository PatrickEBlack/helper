package ie.atu.sw;

import java.io.*;
import java.util.*;
import org.encog.ml.data.basic.BasicMLDataSet;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.layers.BasicLayer;
import org.encog.neural.networks.training.propagation.resilient.ResilientPropagation;
import org.encog.persist.EncogDirectoryPersistence;
import org.encog.ml.data.MLData;
import org.encog.ml.data.basic.BasicMLData;

public class NeuralNetworkController {
    
    private static final int INPUT_SIZE = 61; // 3 columns × 20 rows + 1 player position  
    private static final int HIDDEN_SIZE = 80; // Reduced for simpler network
    private static final int OUTPUT_SIZE = 2; // Only UP or DOWN
    private static final double TARGET_ERROR = 0.01; // Achievable target for simpler network
    private static final int MAX_EPOCHS = 3000;
    
    private BasicNetwork network;
    
    public void createNetwork() {
        network = new BasicNetwork();
        network.addLayer(new BasicLayer(null, true, INPUT_SIZE));
        network.addLayer(new BasicLayer(null, true, HIDDEN_SIZE)); 
        network.addLayer(new BasicLayer(null, false, OUTPUT_SIZE));
        network.getStructure().finalizeStructure();
        network.reset();
        
        System.out.println("Binary action neural network created: " + INPUT_SIZE + "-" + HIDDEN_SIZE + "-" + OUTPUT_SIZE + " (UP/DOWN only)");
    }
    
    public void trainNetwork() throws IOException {
        System.out.println("Loading training data...");
        
        List<double[]> inputs = new ArrayList<>();
        List<double[]> outputs = new ArrayList<>();
        int skippedLines = 0;
        
        try (BufferedReader br = new BufferedReader(new FileReader("resources/training_data.csv"))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                
                // Handle variable length data - take first 60 values + player position + action
                if (parts.length >= 202) { // Expect full 202 field format
                    
                    // Input features: first 60 values (3 columns x 20 rows) + player position
                    double[] input = new double[INPUT_SIZE];
                    for (int i = 0; i < 60; i++) { // First 60 are simplified horizon data
                        input[i] = Double.parseDouble(parts[i]);
                    }
                    
                    // Player position is second-to-last field  
                    input[60] = Double.parseDouble(parts[parts.length - 2]);
                    
                    // Action is last field - skip STAY actions (0)
                    int action = Integer.parseInt(parts[parts.length - 1]);
                    if (action == 0) {
                        skippedLines++;
                        continue; // Skip STAY actions
                    }
                    
                    double[] output = new double[2];
                    if (action == -1) output[0] = 1.0;      // UP
                    else if (action == 1) output[1] = 1.0;  // DOWN
                    
                    inputs.add(input);
                    outputs.add(output);
                } else {
                    skippedLines++;
                }
            }
        }
        
        System.out.println("Skipped " + skippedLines + " malformed lines");
        
        System.out.println("Loaded " + inputs.size() + " training samples");
        
        if (inputs.isEmpty()) {
            System.err.println("No training data found!");
            return;
        }
        
        // Shuffle the data for better training
        Collections.shuffle(inputs);
        Collections.shuffle(outputs);
        
        double[][] inputArray = inputs.toArray(new double[0][]);
        double[][] outputArray = outputs.toArray(new double[0][]);
        
        BasicMLDataSet trainingSet = new BasicMLDataSet(inputArray, outputArray);
        
        if (network == null) {
            createNetwork();
        }
        
        System.out.println("Training neural network...");
        long startTime = System.currentTimeMillis();
        
        // Use ResilientPropagation with proper parameters
        ResilientPropagation train = new ResilientPropagation(network, trainingSet);
        train.setThreadCount(1); // Single threaded for consistency
        
        int epoch = 1;
        double lastError = Double.MAX_VALUE;
        int stagnantEpochs = 0;
        
        do {
            train.iteration();
            double currentError = train.getError();
            
            // Print progress every 50 epochs
            if (epoch % 50 == 0 || epoch == 1) {
                System.out.println("Epoch " + epoch + ", Error: " + String.format("%.6f", currentError));
            }
            
            // Check for stagnation (more sensitive)
            if (Math.abs(lastError - currentError) < 0.00001) {
                stagnantEpochs++;
            } else {
                stagnantEpochs = 0;
            }
            
            lastError = currentError;
            epoch++;
            
            // Stop conditions
            if (currentError <= TARGET_ERROR) {
                System.out.println("Target error reached at epoch " + (epoch-1));
                break;
            }
            
            if (stagnantEpochs > 200) {
                System.out.println("Training stagnant - stopping at epoch " + (epoch-1));
                break;
            }
            
            // Time limit (2 minutes for better convergence)
            if ((System.currentTimeMillis() - startTime) > 120000) {
                System.out.println("Training stopped due to 2 minute time limit");
                break;
            }
            
        } while (epoch <= MAX_EPOCHS);
        
        train.finishTraining();
        
        double trainingTime = (System.currentTimeMillis() - startTime) / 1000.0;
        System.out.println("Training completed in " + String.format("%.1f", trainingTime) + " seconds");
        System.out.println("Final error: " + String.format("%.6f", train.getError()));
        System.out.println("Total epochs: " + (epoch-1));
        
        // Save network
        saveNetwork();
    }
    
    public void saveNetwork() {
        try {
            EncogDirectoryPersistence.saveObject(new File("resources/neural_network.eg"), network);
            System.out.println("Neural network saved");
        } catch (Exception e) {
            System.err.println("Failed to save network: " + e.getMessage());
        }
    }
    
    public boolean loadNetwork() {
        try {
            File file = new File("resources/neural_network.eg");
            if (!file.exists()) {
                return false;
            }
            
            network = (BasicNetwork) EncogDirectoryPersistence.loadObject(file);
            System.out.println("Neural network loaded");
            return true;
        } catch (Exception e) {
            System.err.println("Failed to load network: " + e.getMessage());
            return false;
        }
    }
    
    public int predict(double[] gameState) {
        if (network == null) {
            return 0; // Default to stay
        }
        
        if (gameState.length != INPUT_SIZE) {
            System.err.println("Invalid input size: expected " + INPUT_SIZE + ", got " + gameState.length);
            return 0;
        }
        
        MLData input = new BasicMLData(gameState);
        MLData output = network.compute(input);
        
        // Find the highest output (winner takes all)
        double[] data = output.getData();
        int maxIndex = 0;
        for (int i = 1; i < data.length; i++) {
            if (data[i] > data[maxIndex]) {
                maxIndex = i;
            }
        }
        
        // Log network decision for debugging
        String action = maxIndex == 0 ? "UP" : "DOWN";
        System.out.println(String.format("NN: %s (UP=%.3f, DOWN=%.3f)", 
                          action, data[0], data[1]));
        
        // Convert back to action (binary: UP or DOWN)
        switch (maxIndex) {
            case 0: return -1; // UP
            case 1: return 1;  // DOWN  
            default: return -1; // Default to UP
        }
    }
    
    public boolean isReady() {
        return network != null;
    }
}