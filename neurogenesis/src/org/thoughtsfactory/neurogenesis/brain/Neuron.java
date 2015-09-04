/**
 * 
 */
package org.thoughtsfactory.neurogenesis.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

import org.apache.log4j.Logger;

import repast.simphony.context.Context;
import repast.simphony.engine.schedule.ScheduleParameters;
import repast.simphony.engine.schedule.ScheduledMethod;
import repast.simphony.query.space.grid.GridCell;
import repast.simphony.query.space.grid.GridCellNgh;
import repast.simphony.random.RandomHelper;
import repast.simphony.space.continuous.ContinuousSpace;
import repast.simphony.space.graph.Network;
import repast.simphony.space.graph.RepastEdge;
import repast.simphony.space.grid.Grid;
import repast.simphony.space.grid.GridPoint;
import repast.simphony.util.ContextUtils;
import repast.simphony.util.SimUtilities;


/**
 * @author bob
 *
 */
public class Neuron extends GeneRegulatedCell {


	/**
	 * 
	 */
	public static int MAX_DENDRITE_ROOTS = 25;
	
	
	/**
	 * 
	 */
	public static int MAX_DENDRITE_LEAVES = 2;
	
	
	/**
	 * 
	 */
	public static double LEARNING_RATE = 0.2;
	
	
	//
	private final static Logger logger = Logger.getLogger(Neuron.class);	
		
	
	/**
	 * 
	 */
	protected final Network<Object> neuralNetwork;
	
	
	/**
	 * 
	 */
	protected final Network<Object> neuritesNetwork;
	
	
	/**
	 * 
	 */
	protected NeuriteJunction axonTip = null;
	
	
	/**
	 * 
	 */
	protected NeuriteJunction neuritesRoot = null;
	
	
	/**
	 * 
	 */
	protected double activation;
	
	
	/**
	 * 
	 */
	protected List<NeuriteJunction> dendriteLeaves = 
			new ArrayList<NeuriteJunction>();
	
	
	/**
	 * 
	 */
	protected Queue<NeuriteJunction> freeDendriteLeavesPool = 
			new PriorityQueue<NeuriteJunction>(
					30, new NeuriteDepthComparator());
	
	
	/**
	 * 
	 * @param space
	 * @param grid
	 */
	public Neuron(final ContinuousSpace<Object> newSpace, 
			final Grid<Object> newGrid,
			final RegulatoryNetwork newRegulatoryNetwork,
			final Network<Object> newNeuralNetwork,
			final Network<Object> newNeuritesNetwork,
			final boolean newCellAdhesionEnabled) {
		
		super(newSpace, newGrid, newRegulatoryNetwork, newCellAdhesionEnabled);
		
		this.neuralNetwork = newNeuralNetwork;
		this.neuritesNetwork = newNeuritesNetwork;
		
	} // End of Neuron(ContinuousSpace, Grid, RegulatoryNetwork, Network)

	
	/**
	 * 
	 * @param baseCell
	 */
	public Neuron(final GeneRegulatedCell motherCell, 
			final Network<Object> newNeuralNetwork,
			final Network<Object> newNeuritesNetwork) {
		
		super(motherCell);
				
		this.neuralNetwork = newNeuralNetwork;
		this.neuritesNetwork = newNeuritesNetwork;
		
		this.membraneChannels.get(CellProductType.SAM).setOpenForOutput(true);
		
	} // End of Neuron(GeneRegulatedCell)
	
	
	/**
	 * 
	 * @return
	 */
	public double getActivation() {
		return this.activation;
	};
	

	/**
	 * 
	 */
	@ScheduledMethod(start = 1, interval = 1, 
			priority = ScheduleParameters.RANDOM_PRIORITY)
	public void step() {

		calculateActivation();
		
		absorbProductsFromMatrix();
		updateRegulatoryNetwork();
		updateCellConcentrations();
				
		// Handles cell death.
		if (!cellDeathHandler()) {
			
			// Handles neurites growth.
			initialiseNeurites(true, true);
			cellAxonGrowthHandler();
			cellDendritesGrowthHandler();
			
			// Handles cell adhesion.
//			if (this.cellAdhesionEnabled) {
//				cellAdhesionHandler();
//			}

			// Handles mutations.
			//cellMutationHandler();
			
			// Handles movement.
			//cellMovementHandler();
			
			expelProductsToMatrix();

		} // End if()
		
	} // End of step()

	
	/**
	 * 
	 */
	@Override
	protected boolean cellDeathHandler() {
		
		double wasteConcentration = this.membraneChannels
				.get(CellProductType.WASTE).getConcentration();
		
		logger.debug("Cell death waste concentration: "	+ wasteConcentration);

		double foodConcentration = this.membraneChannels
				.get(CellProductType.FOOD).getConcentration();
		
		if ((wasteConcentration > REGULATOR_UNIVERSAL_THRESHOLD)
				|| (foodConcentration == 0)) {
			
			// Remove the axon first so as to not 
			// attempt to remove the root twice.
			removeNeuriteJunction(this.axonTip);
			removeNeuriteJunction(this.neuritesRoot);
			
			@SuppressWarnings("unchecked")
			Context<Object> context = ContextUtils.getContext(this);
			
			for (NeuriteJunction junction : this.freeDendriteLeavesPool) {
				context.remove(junction);
			}
			
			this.alive = false;
			
			context.remove(this);
			logger.info("Neuron death event: food = " + foodConcentration 
					+ ", waste = " + wasteConcentration);
			return true;

		} // End if()
				
		return false;
		
	} // End of cellDeathHandler)_
	

	/**
	 * 
	 * @param currentJunction
	 */
	protected void removeNeuriteJunction(
			final NeuriteJunction currentJunction) {
				
		currentJunction.setActive(false);

		@SuppressWarnings("unchecked")
		Context<Object> context = ContextUtils.getContext(this);
		context.remove(currentJunction);

		for (NeuriteJunction nextJunction : currentJunction.getPredecessors()) {

			// Ignore the root as predecessor in an axon.
			if (nextJunction.getType() == NeuriteJunction.Type.NEURON) {
				continue;
			}
			
			// Do not collect beyond a synapse!
			if ((currentJunction.getType() == NeuriteJunction.Type.DENDRITE)
					&& (nextJunction.getType() == NeuriteJunction.Type.AXON)) {
				nextJunction.getSynapses().remove(currentJunction);
			} else {
				removeNeuriteJunction(nextJunction);
			}
			
		} // End for(nextJunction)
		
	} // End of removeNeuriteJunction()
	
	
	/**
	 * 
	 * @return
	 */
	public boolean initialiseNeurites(final boolean createAxon, 
			final boolean createDendrites) {
		
		if (this.neuritesRoot == null) {
			
			// Creates the root to all neurites.
			
			this.neuritesRoot = 
					new NeuriteJunction(NeuriteJunction.Type.NEURON, this, 0);
			
			@SuppressWarnings("unchecked")
			Context<Object> context = ContextUtils.getContext(this);
			context.add(this.neuritesRoot);

			GridPoint pt = this.grid.getLocation(this);
			this.space.moveTo(this.neuritesRoot, pt.getX() + 0.5, 
					pt.getY() + 0.5, pt.getZ() + 0.5); 
			this.grid.moveTo(this.neuritesRoot, 
					pt.getX(), pt.getY(), pt.getZ());
			
			// Creates the axon.
			
			if (createAxon) {
				this.axonTip = extendNeurite(NeuriteJunction.Type.AXON, 
						this.neuritesRoot, false);
			}
			
			// Creates the initial dendrites.
			
			if (createDendrites) {
				
				for (int n = 1; 
						n <= Math.min(MAX_DENDRITE_ROOTS, MAX_DENDRITE_LEAVES); 
						n++) {
				
					if ((n == 1) || (RandomHelper.nextDoubleFromTo(0, 1) 
							<= this.cellGrowthRegulator)) {
					
						NeuriteJunction newDendrite = 
								extendNeurite(NeuriteJunction.Type.DENDRITE, 
										this.neuritesRoot, false);
					
						if (newDendrite == null) {
							break;
						}
									
						this.dendriteLeaves.add(newDendrite);
						
					} // End if()
				
				} // End for()
			
			} // End  if()
			
			return true;

		} // End if()
		
		return false;
		
	} // End of initialiseNeurites()
	
	
	/**
	 * 
	 * @return
	 */
	protected boolean cellAxonGrowthHandler() {
		
		logger.debug("Cell axon growth regulator concentration: " 
				+ this.cellGrowthRegulator);
		
		if (this.checkConcentrationTrigger(this.cellGrowthRegulator, false)) {

			NeuriteJunction newJunction = 
					extendNeurite(NeuriteJunction.Type.AXON, 
							this.axonTip, true);

			if (newJunction != null) {
				this.axonTip = newJunction;
				return true;				
			}

		} // End if()
		
		return false;
		
	} // End of cellAxonGrowthHandler

	
	/**
	 * 
	 * @return
	 */
	protected boolean cellDendritesGrowthHandler() {
		
		logger.debug("Cell dendrites growth regulator concentration: " 
				+ this.cellGrowthRegulator);
		
		if (this.checkConcentrationTrigger(this.cellGrowthRegulator, false)) {

			NeuriteJunction nextBud = null;
			double minValue = Double.MAX_VALUE;
	
			for (NeuriteJunction dendriteLeaf : this.dendriteLeaves) {
							
				logger.debug("Searching: dendrite depth = "	
						+ dendriteLeaf.getDepth());
				
				GridPoint dendriteLocation = 
						this.grid.getLocation(dendriteLeaf);
				if (dendriteLocation == null) {
					logger.warn("Dendrite location is null! (Neuron is " 
							+ ((dendriteLeaf.getNeuron().alive) 
									? "alive" : "dead") + ")");
				}
				
				Map<CellProductType, Double> externalConcentrations = 
						getExternalConcentrations(dendriteLocation);
			
				double externalConcentration = 
						externalConcentrations.get(CellProductType.SAM);
				
				double currentValue = 
						externalConcentration * dendriteLeaf.getDepth();
				if (currentValue < minValue) {
					nextBud = dendriteLeaf;
					minValue = currentValue;
				}
				
			} // End for()
			
			// First branch from bud.

			NeuriteJunction newJunction1 = 
					extendNeurite(NeuriteJunction.Type.DENDRITE, nextBud, 
							this.dendriteLeaves.size() > 1);

			if (newJunction1 == null) {
				return false;
			}
			
			logger.debug("Number of leaves: " + this.dendriteLeaves.size());

			// In any cases, selected dendrite is a leaf no longer.
			this.dendriteLeaves.remove(nextBud);
			
			logger.debug("Number of leaves (removed bud): " 
					+ this.dendriteLeaves.size());

			// Add the new leaf to the list if not a synapse.
			if (newJunction1.getType() != NeuriteJunction.Type.AXON) {
				this.dendriteLeaves.add(newJunction1);
				logger.debug("Number of leaves (added J1): " 
						+ this.dendriteLeaves.size());
			}
			
			// Second (optional) branch from bud.
			
			if (RandomHelper.nextDoubleFromTo(0, 1)
					> this.membraneChannels.get(CellProductType.SAM)
					.getConcentration()) {
				
				NeuriteJunction newJunction2 = 
						extendNeurite(NeuriteJunction.Type.DENDRITE, nextBud, 
								this.dendriteLeaves.size() > 1);
				
				if (newJunction2 == null) {
					// At this point the first branch at least 
					// was added successfully.
					return true;
				}

				if (newJunction2.getType() != NeuriteJunction.Type.AXON) {
					
					// Need to add the second dendrite and possibly expel one
					// dendrite from the table if it is full.
						
					this.dendriteLeaves.add(newJunction2);
					logger.debug("Number of leaves (added J2): " 
							+ this.dendriteLeaves.size());
					
					if (this.dendriteLeaves.size() > MAX_DENDRITE_LEAVES) {
						
						/* Leaves that are expelled from the list won't ever be
						 * candidate again as the root of new buds, hence they
						 * can be discarded. Also, only new dendrites have the
						 * opportunity to connect to axons.
						 */
						
						discardDendriteLeaf();
												
					} // End if()
					
				} // End if()
				
			} // End if ()
			
			return true;

		} // End if()
		
		return false;
		
	} // End of cellDendritesGrowthHandler()


	/**
	 * 
	 */
	protected void discardDendriteLeaf() {
		
		logger.info("Discarding dendrite leaves...");

		NeuriteJunction dendriteToRemove = this.dendriteLeaves.get(0);
		for (NeuriteJunction dendriteLeaf : this.dendriteLeaves) {
			if (dendriteLeaf.getDepth() < dendriteToRemove.getDepth()) {
				dendriteToRemove = dendriteLeaf;
			}
		}
		
		this.dendriteLeaves.remove(dendriteToRemove);
		logger.debug("Number of leaves (removed deepest): " 
				+ this.dendriteLeaves.size());

		boolean done = false;
		
		while (!done) {
			
			// Moving up the tree...
			NeuriteJunction successor =	dendriteToRemove.getSuccessor();
	
			dendriteToRemove.setActive(false);
			
			RepastEdge<Object> edgeToRemove = 
					this.neuritesNetwork.getEdge(dendriteToRemove, 
							dendriteToRemove.getSuccessor());
			if (edgeToRemove == null) {
				throw new IllegalStateException(
						"No edge between junctions!");
			}

			this.neuritesNetwork.removeEdge(edgeToRemove);

			dendriteToRemove.getPredecessors().clear();
			this.freeDendriteLeavesPool.add(dendriteToRemove);
			
			logger.debug("Dendrite depth " 
					+ dendriteToRemove.getDepth() + " added to pool.");
	
			// The successor is not a leaf?
			List<NeuriteJunction> predecessors = successor.getPredecessors();
			if (predecessors.size() > 1) {
				predecessors.remove(dendriteToRemove);
				done = true;
			} else {
				dendriteToRemove = successor;
			}
			
		} // End while()

	} // End of discardDendriteLeaf()

	
	/**
	 * 
	 */
	protected NeuriteJunction extendNeurite(
			final NeuriteJunction.Type newJunctionType,
			final NeuriteJunction currentJunction,
			final boolean findSynapses) {
		
		if (newJunctionType == NeuriteJunction.Type.NEURON) {
			throw new IllegalArgumentException("AXON or DENDRITE only!");
		}
		
		GridPoint currentLocation =	this.grid.getLocation(currentJunction);
		if (currentLocation == null) {
			logger.warn("Current location is null!!!");
		}
		
		// Use the GridCellNgh class to create GridCells for
		// the surrounding neighbourhood.
		GridCellNgh<NeuriteJunction> nghCreator = 
				new GridCellNgh<NeuriteJunction>(this.grid, currentLocation, 
						NeuriteJunction.class, 1, 1, 1);
		List<GridCell<NeuriteJunction>> gridCells =	
				nghCreator.getNeighborhood(false);
		SimUtilities.shuffle(gridCells, RandomHelper.getUniform());

		// Pick the first free grid cell among the shuffled list.
		
		GridCell<NeuriteJunction> selectedGridCell = null;
		double minConcentration = Double.MAX_VALUE;
		NeuriteJunction newJunction = null;
		
		// Synapses are not created from a root NEURON type.
		final boolean lookForSynapse = findSynapses 
				&& (newJunctionType == NeuriteJunction.Type.DENDRITE)
				&& (currentJunction.getType() == NeuriteJunction.Type.DENDRITE);
		
		for (GridCell<NeuriteJunction> gridCell : gridCells) {
			
			boolean freeCell = true;
			NeuriteJunction synapse = null;
			
			if (gridCell.size() > 0) {
				
				for (NeuriteJunction junction : gridCell.items()) {

					if (!junction.isActive()) {
						continue;
					}
					
					if (junction.getNeuron() == this) {
						freeCell = false;
						if (!lookForSynapse) {
							break;
						}
					} else if (lookForSynapse &&	
							(junction.getType() == NeuriteJunction.Type.AXON)) {
						synapse = junction;
						break;
					}
											
				} // End for(junction)
						
			} // End if()
			
			if (synapse == null) {
				
				if (freeCell) {
					
					Map<CellProductType, Double> externalConcentrations = 
							getExternalConcentrations(gridCell.getPoint());
					double samConcentration = 
							externalConcentrations.get(CellProductType.SAM);
					if (samConcentration < minConcentration) {
						minConcentration = samConcentration;
						selectedGridCell = gridCell;
					}
					
				} // End if()
				
			} else {
									
				newJunction = synapse;
				selectedGridCell = gridCell;
				break;
					
			} // End if()
			
		} // End for(gridCell)
		
		if (selectedGridCell == null) {
			return null;
		}
		
		if (newJunction == null) {
			
			if ((newJunctionType == NeuriteJunction.Type.AXON) 
					|| this.freeDendriteLeavesPool.isEmpty()) {
				
				// Create the new junction.
			
				newJunction = new NeuriteJunction(newJunctionType, 
						this, currentJunction.getDepth() + 1);
			
				@SuppressWarnings("unchecked")
				Context<Object> context = ContextUtils.getContext(this);

				context.add(newJunction);

				logger.debug("Created new junction: " + newJunction.getType());
				
			} else {
				
				// Recycle a free dendrite leaf.
				
				newJunction = this.freeDendriteLeavesPool.remove();
				
				newJunction.setDepth(currentJunction.getDepth() + 1);
				newJunction.setActive(true);
				
				logger.debug("Recycled junction: " + newJunction.getType());
				
			} // End if();
			
			GridPoint newJunctionLocation = selectedGridCell.getPoint();
			
			this.space.moveTo(newJunction, 
					getNewNeuriteSpacePos(currentLocation.getX(), 
							newJunctionLocation.getX()), 
					getNewNeuriteSpacePos(currentLocation.getY(), 
							newJunctionLocation.getY()), 
					getNewNeuriteSpacePos(currentLocation.getZ(), 
							newJunctionLocation.getZ())); 
			this.grid.moveTo(newJunction, newJunctionLocation.getX(), 
					newJunctionLocation.getY(), newJunctionLocation.getZ());

			if (newJunctionType == NeuriteJunction.Type.DENDRITE) {
				
				// Create a new dendrite leaf.
				currentJunction.getPredecessors().add(newJunction);
				newJunction.setSuccessor(currentJunction);
				this.neuritesNetwork.addEdge(newJunction, currentJunction);
				
			} else {
				
				// Create a new axon junction.
				newJunction.getPredecessors().add(currentJunction);
				currentJunction.setSuccessor(newJunction);
				
				this.neuritesNetwork.addEdge(currentJunction, newJunction);
				
			} // End if()
						
		} else {
		
			// Create the dendrite synapse.
			
			currentJunction.getPredecessors().add(newJunction);
			newJunction.getSynapses().add(currentJunction);
			
			this.neuritesNetwork.addEdge(newJunction, currentJunction);
			this.neuralNetwork.addEdge(newJunction.getNeuron(), this, 
					RandomHelper.nextDoubleFromTo(-1, 1));
		
			logger.info("New synapse created.");
			
		} // End if()
		
		return newJunction;
		
	} // End of extendNeurite()

	
	/**
	 * 
	 * @param sourcePos
	 * @return
	 */
	protected double getNewNeuriteSpacePos(final int sourcePos, 
			final int targetPos) {

		// Gives the sign, or direction.
		int deltaPos = targetPos - sourcePos;
		
		if (deltaPos == 0) {
			// Same plane: length of cell.
			return targetPos + RandomHelper.nextDoubleFromTo(0, 1);
		} else if (deltaPos < 0) {
			// Behind: border 0.2 thick.
			return targetPos + 0.8 + RandomHelper.nextDoubleFromTo(0, 0.2);
		} else {
			// In front: border 0.2 thick.
			return targetPos + RandomHelper.nextDoubleFromTo(0, 0.2);
		}
		
	} // End of getNewNeuriteSpacePos()
	
	
	/**
	 * 
	 * @param centrePos
	 * @param refPos
	 * @param newPos
	 * @return
	 */
	protected boolean isOnTheSameSide(final int centrePos, 
			final int refPos, final int newPos) {
		
		return (refPos > centrePos && newPos > centrePos) 
				|| (refPos < centrePos && newPos < centrePos);
		
	} // End of isOnTheSameSide()
	
	
	/**
	 * 
	 */
	private void calculateActivation() {
		
		double netInput = 0;
		
		for (Object obj : this.neuralNetwork.getPredecessors(this)) {
			if (obj instanceof Neuron) {
				Neuron neuron = (Neuron) obj;
				RepastEdge<Object> edge = this.neuralNetwork.getEdge(neuron, this);
				netInput += neuron.getActivation() * edge.getWeight();
			}
		}
		
		// Sigmoid function.
		this.activation = (1 / (1 + Math.pow(Math.E, -1 * netInput)));
		
		// Ajust the weight using the Hebbian rule.
		for (Object obj : this.neuralNetwork.getPredecessors(this)) {
			if (obj instanceof Neuron) {
				Neuron neuron = (Neuron) obj;
				RepastEdge<Object> edge = this.neuralNetwork.getEdge(neuron, this);
				double newWeight = LEARNING_RATE * this.activation 
						* (neuron.activation - neuron.activation 
								* edge.getWeight());
				edge.setWeight(newWeight);
			}
		}
		
	} // calculateActivation()

	
	/** 
	 */
	protected Cell clone() {
		throw new IllegalStateException("Neurons cannot be cloned!");
	}
	
	
} // End of Neuron class