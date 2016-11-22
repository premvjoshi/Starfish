/* *****************************************************
 * (c) 2012 Particle In Cell Consulting LLC
 * 
 * This document is subject to the license specified in 
 * Starfish.java and the LICENSE file
 * *****************************************************/
package starfish.core.materials;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import org.w3c.dom.Element;
import starfish.core.boundaries.Boundary;
import starfish.core.boundaries.Segment;
import starfish.core.common.Constants;
import starfish.core.common.Starfish;
import starfish.core.common.Starfish.Log;
import starfish.core.domain.DomainModule.DomainType;
import starfish.core.domain.Field2D;
import starfish.core.domain.Mesh;
import starfish.core.domain.Mesh.BoundaryData;
import starfish.core.domain.Mesh.Face;
import starfish.core.domain.Mesh.Node;
import starfish.core.domain.Mesh.NodeType;
import starfish.core.domain.UniformMesh;
import starfish.core.io.InputParser;
import starfish.core.materials.MaterialsModule.MaterialParser;
import starfish.core.common.Vector;

/** definition of particle-based material*/
public class KineticMaterial extends Material
{
    public KineticMaterial(String name, double mass, double charge, double spwt0)
    {
	super(name, mass, charge);

	this.spwt0 = spwt0;
    }
    /*specific weight*/
    protected double spwt0;

    public double getSpwt0()
    {
	return spwt0;
    }
    protected long part_id_counter = 0;

    @Override
    public void init()
    {
	/*call up*/
	super.init();

	/*allocate memory*/
	ArrayList<Mesh> mesh_list = Starfish.getMeshList();
	mesh_data = new MeshData[mesh_list.size()];
	for (int m = 0; m < mesh_list.size(); m++)
	{
	    mesh_data[m] = new MeshData(mesh_list.get(m));
	}
    }

    @Override
    public void updateFields()
    {
	/*clear all fields*/
	for (MeshData md : mesh_data)
	{
	    getDen(md.mesh).clear();
	    getU(md.mesh).clear();
	    getV(md.mesh).clear();
	    getT(md.mesh).clear();
	}

	total_momentum = 0;
	/*first loop through all particles*/
	for (MeshData md : mesh_data)
	{
	    moveParticles(md, false);
	}

	/*now move transferred particle*/
	/*TODO: multiple loops*/
	for (MeshData md : mesh_data)
	{
	    moveParticles(md, true);
	}

	/*update densities and velocities*/
	for (MeshData md : mesh_data)
	{
	    updateFields(md);
	}

	/*scale momentum by mass*/
	total_momentum *= mass;

	/*compute temperature*/
	/*TODO: this could be done on demand as it may not be used often*/
	for (MeshData md : mesh_data)
	{
//	    computeT(md);
	}

	/*apply boundaries*/
	updateBoundaries();
    }

    /**
     * uses final particle positions to update fields
     */
    void updateFields(MeshData md)
    {
	Field2D Den = getDen(md.mesh);
	Field2D U = getU(md.mesh);
	Field2D V = getV(md.mesh);

	/*first get average velocities*/
	U.divideByField(Den);
	V.divideByField(Den);

	/*now turn density actually into density*/
	Den.scaleByVol();
    }

    /*updates field on a single mesh*/
    void moveParticles(MeshData md, boolean particle_transfer)
    {
	//ArrayList <Thread> group = new ArrayList();
	
	for (int block=0;block<md.particle_block.length;block++)
	{
	    Iterator<Particle> iterator;
	    
	    if (!particle_transfer) iterator=md.getIterator(block);
	    else iterator=md.getTransferIterator(block);
	    
	    ParticleMover mover = new ParticleMover(md,iterator,particle_transfer,"PartMover"+block);  
	    mover.run();
//	    group.add(mover);
//	    mover.start();
	}
	
	/*wait for threads to finish*/
//	try {
//	    for (Thread thread: group) {thread.join();}
//	}
//	catch (InterruptedException e)
//	{
//	    Log.warning("InterruptedException\n");
//	}
	
    }

    /*returns a particle iterator that iterates over all blocks*/
    public Iterator<Particle> getIterator(Mesh mesh)
    {
	return getMeshData(mesh).getIterator();
    }
    
    
    /*updates particles on a single block*/
    class ParticleMover extends Thread
    {
  
	protected MeshData md;
	protected Iterator<Particle> iterator;
	protected boolean particle_transfer;

	private ParticleMover(MeshData md, Iterator<Particle> iterator, boolean particle_transfer, String thread_name)
	{
	    super(thread_name);
	    
	    this.md=md;
	    this.iterator = iterator;
	    this.particle_transfer=particle_transfer;
	}
	
	@Override
	public void run()
	{
	    final int max_bounces = 10;		/*maximum number of surface bounces per step*/
	    double old[] = new double[2];	/*old physical coordinate*/
	    double old_lc[] = new double[2]; /*old logical coordinate*/
	    double ef[] = new double[3];
	    double bf[] = new double[3];

	    Mesh mesh = md.mesh;
	    Field2D Den = getDen(mesh);
	    Field2D U = getU(mesh);
	    Field2D V = getV(mesh);

	    Field2D Efi = md.Efi;
	    Field2D Efj = md.Efj;

	    Field2D Bfi = md.Bfi;
	    Field2D Bfj = md.Bfj;

	    while (iterator.hasNext())
	    {
		Particle part = iterator.next();

		/*increment particle time*/
		if (!particle_transfer)
		{
		    part.dt += Starfish.getDt();
		}

	    /*update velocity*/
	    ef[0] = Efi.gather(part.lc);
	    ef[1] = Efj.gather(part.lc);

	    /*update velocity*/
	    bf[0] = Bfi.gather(part.lc);
	    bf[1] = Bfj.gather(part.lc);

	    /*update velocity*/
	    if (bf[0] == 0 && bf[1] == 0)
	    {
		part.vel[0] += q_over_m * ef[0] * part.dt;
		part.vel[1] += q_over_m * ef[1] * part.dt;
	    } else
	    {
		UpdateVelocityBoris(part, ef, bf);
	    }

	    int bounces = 0;
	    boolean alive = true;

	    /*iterate while we have time remaining*/
	    while (part.dt > 0 && bounces++ < max_bounces)
	    {
		/*save old position*/
		old[0] = part.pos[0];
		old[1] = part.pos[1];
		    
		old_lc[0] = part.lc[0];
		old_lc[1] = part.lc[1];

		/*update position*/
		part.pos[0] += part.vel[0] * part.dt;
		part.pos[1] += part.vel[1] * part.dt;
		double dk = part.vel[2]*part.dt;
		
		if (Starfish.getDomainType()==DomainType.RZ)
		{
		    rotateToRZ(part,dk);
		}
		else if (Starfish.getDomainType()==DomainType.ZR)
		{
		    rotateToZR(part,dk);
		}		
		else
		    part.pos[2] += dk;

		part.lc = mesh.XtoL(part.pos);
		
		/*check if particle hit anything or left the domain*/
		alive = ProcessBoundary(part, mesh, old, old_lc);
	
		if (!alive)
		{
		    iterator.remove();
		    break;
		}
		
		/*are we tracing this particle, if so output trace*/
		if (part.trace_id>=0)
		    Starfish.particle_trace_module.addTrace(part);	
	    } /*dt*/

	    /*TODO: make thread safe!*/
	    /*scatter data*/
	    if (alive)
	    {		
		synchronized(this){
		Den.scatter(part.lc, part.spwt);
		U.scatter(part.lc, part.vel[0] * part.spwt);
		V.scatter(part.lc, part.vel[1] * part.spwt);

		/*also add to the main list if transfer*/
		if (particle_transfer)
		{
		    md.addParticle(part);
		    iterator.remove();
		}

		/*save momentum for diagnostics, will be multiplied by mass in updatefields*/
		total_momentum += part.spwt * Vector.mag2(part.vel);
		}
	    }
	}	/*end of particle loop*/
    }

	private void rotateToRZ(Particle part, double dk)
	{
	    /*movement in R plane*/
	    double A = dk;
	    double B = part.pos[0];		/*new position in R plane*/
	    part.pos[0] = Math.sqrt(B*B + A*A);		/*new radius*/

	    double cos = B/part.pos[0];
	    double sin = A/part.pos[0];
	    double theta = Math.asin(sin);
	    part.pos[2]+=theta;	/*update theta*/

	    /*rotate XY velocity*/
	    double u = part.vel[0];
	    double v = part.vel[2];
	    part.vel[0] = cos*u+sin*v;
	    part.vel[2] = -sin*u+cos*v;	
	}
	
	private void rotateToZR(Particle part, double dk)
	{
	    /*movement in R plane*/
	    double A = dk;
	    double B = part.pos[1];		/*new position in R plane*/
	    part.pos[1] = Math.sqrt(B*B + A*A);		/*new radius*/

	    double cos = B/part.pos[1];
	    double sin = -A/part.pos[1];    /*positive in the negative k direction*/
	    double theta = Math.asin(sin);
	    part.pos[2]+=theta;	/*update theta*/

	    /*rotate velocity through theta*/
	    double v1 = part.vel[1];
	    double v2 = part.vel[2];
	    part.vel[1] = cos*v1 - sin*v2;
	    part.vel[2] = sin*v1 + cos*v2;
	    
	}
    }
  
    /**
     * checks for particle surface hits and/or domain escape
     *
     *
     * @param id	return value, contains info about impact location
     * @return remaining dt, or -1 if absorbed
     */
    boolean ProcessBoundary(Particle part, Mesh mesh, double old[], double lc_old[])
    {
	boolean left_mesh = false;
	Face exit_face = null;

	double dt0 = part.dt;	/*initial time step*/
	part.dt = 0;		/*default, used up all time*/

	/*capture bounding box of particle motion and particle position before pushing
	 *particle into domain*/
	double lc_min[] = new double[2];
	double lc_max[] = new double[2];
	lc_min[0] = Math.min(part.lc[0], lc_old[0]);
	lc_min[1] = Math.min(part.lc[1], lc_old[1]);
	lc_max[0] = Math.max(part.lc[0], lc_old[0]);
	lc_max[1] = Math.max(part.lc[1], lc_old[1]);

	int i_min = (int) lc_min[0];
	int i_max = (int) lc_max[0];
	int j_min = (int) lc_min[1];
	int j_max = (int) lc_max[1];
	
	/*verify above min/max are in range*/
	if (i_min<0) i_min=0;
	if (j_min<0) j_min=0;
	if (i_max>=mesh.ni) i_max=mesh.ni-1;
	if (j_max>=mesh.nj) j_max=mesh.nj-1;
	
	/*assemble a list of segments in this block*/
	Set<Segment> segments = new HashSet();
	
	/*make a set of all segments in the bounding box*/
	for (int i = i_min; i <= i_max; i++)
	    for (int j = j_min; j <= j_max; j++)
	    {
		Node node = mesh.getNode(i, j);

		for (Segment seg : node.segments)
		    if (seg.getBoundaryType() == NodeType.DIRICHLET)
			segments.add(seg);
	    }

	/*iterate over the segments and find the first one to be hit*/
	double tp_min=2.0,tsurf_min=0;
	Segment seg_min = null;
	for (Segment seg:segments)
	{
	    /*t[0] is the location along the surface, t[1] is location along particle vector*/
	    double t[] = seg.intersect(old, part.pos);
	    double t_part = t[1];
	    
	    /*do we have an intersection, excluding starting point?*/
	    /*todo: need to consider velocity direction, only makes sense if moving away from surface*/
	   // if (t_part > 1e-10)
	    if (t_part>=0)
	    {
		/*skip over particles that collide with surface at the beginning of their time step,
		 * as long as they are moving away from the surface*/
		double acos=Vector.dot2(seg.normal(t[0]),part.vel)/Vector.mag2(part.vel);
		if (t_part<Constants.FLT_EPS && acos>0) continue;
		
		/*is this a new minimum?*/
		if (t_part<tp_min)
		{
		    tp_min=t_part;
		    tsurf_min=t[0];
		    seg_min=seg;
		}
	    }
	}
	
	/*perform intersection*/
	if (seg_min!=null)
	{
	    /*set dt_rem*/
	    part.dt = dt0 * (1 - tp_min);

	    /*move to surface*/
	    part.pos[0] = old[0] + tp_min * (part.pos[0] - old[0]);
	    part.pos[1] = old[1] + tp_min * (part.pos[1] - old[1]);
	    part.lc = mesh.XtoL(part.pos);

	    /*call handler*/
	    Boundary boundary_hit = seg_min.getBoundary();
	    Material target_mat = boundary_hit.getMaterial(tsurf_min);
	    double boundary_t = seg_min.id()+tsurf_min;

	    /*perform surface interaction*/
	    boolean alive = false;
	    if (target_mat!=null)
		alive = target_mat.performSurfaceInteraction(part.vel, mat_index, seg_min, tsurf_min);

	    /*deposit flux and deposit, if stuck*/
	    addSurfaceMomentum(boundary_hit, boundary_t, part.vel, part.spwt);

	    if (!alive)
	    {
		/*we will multiply by mass in "finish"*/
		
		addSurfaceMassDeposit(boundary_hit, boundary_t, part.spwt);
/*testing*/
		/*
if (Starfish.steady_state())
{
    if (seg_min.boundary.getName().equalsIgnoreCase("INLET")) Starfish.inlet++;
    else if (seg_min.boundary.getName().equalsIgnoreCase("WALL")) Starfish.wall++;
    else if (seg_min.boundary.getName().equalsIgnoreCase("OUTLET")) Starfish.outlet++;   		
}*/
	    }
	    
	    return alive;
	}
  
	/*did particle leave the domain*/
	if (part.lc[0] < 0 || part.lc[1] < 0
	    || part.lc[0] >= mesh.ni - 1 || part.lc[1] >= mesh.nj - 1)
	{
	    
	    
	    /*determine exit face*/
	    double t_right = 99, t_top = 99, t_left = 99, t_bottom = 99;

	    if (part.lc[0] >= mesh.ni - 1)
	    {
		/*using >1.0 to place particle inside domain*/
		t_right = (mesh.ni - 1.000001 - lc_old[0]) / (part.lc[0] - lc_old[0]);
	    }
	    if (part.lc[1] >= mesh.nj - 1)
	    {
		t_top = (mesh.nj - 1.000001 - lc_old[1]) / (part.lc[1] - lc_old[1]);
	    }
	    if (part.lc[0] < 0)
	    {
		t_left = lc_old[0] / (lc_old[0] - part.lc[0]);
	    }
	    if (part.lc[1] < 0)
	    {
		t_bottom = lc_old[1] / (lc_old[1] - part.lc[1]);
	    }

	    exit_face = Face.RIGHT;
	    double t = t_right;

	    if (t_top < t)
	    {
		exit_face = Face.TOP;
		t = t_top;
	    }
	    if (t_left < t)
	    {
		exit_face = Face.LEFT;
		t = t_left;
	    }
	    if (t_bottom < t)
	    {
		exit_face = Face.BOTTOM;
		t = t_bottom;
	    }

	    /*find boundary position, cannot use linear expression on position since
	     mapping from physical to logical may not be linear*/
	    part.lc[0] = lc_old[0] + t * (part.lc[0] - lc_old[0]);
	    part.lc[1] = lc_old[1] + t * (part.lc[1] - lc_old[1]);
	    
	    double x[] = mesh.pos(part.lc);

	    /*update particle position, part.pos is double[3], wall pos is double[2]*/
	    part.pos[0] = x[0];
	    part.pos[1] = x[1];

	    left_mesh = true;
	    part.dt = dt0 * (1 - t);	/*update remaining dt*/
	} /*if left mesh*/

	/*if particle left mesh, check through what boundary it left*/
	if (left_mesh)
	{    
	    NodeType type = mesh.nodeType((int)part.lc[0], (int)part.lc[1]);
	    switch (type)
	    {
		case OPEN:
		    return false;
		case SYMMETRY: 
		    /*grab normal vector*/
		    double n[] = mesh.boundaryNormal(exit_face,part.pos);
		    part.vel = Vector.mirror(part.vel,n);
		    return true;
		case PERIODIC: /*TODO: implemented only for single mesh*/
		    UniformMesh um = (UniformMesh)mesh;
		    if (exit_face == Face.LEFT)
		  	part.pos[0] += (um.xd[0]-um.x0[0]);
		    else if (exit_face ==Face.RIGHT)
		    	part.pos[0] -= (um.xd[0]-um.x0[0]);
		    else if (exit_face== Face.BOTTOM)
			part.pos[1] += (um.xd[1]-um.x0[1]);
		    else
			part.pos[1] -= (um.xd[1]-um.x0[1]);
		    return true;
		case MESH: /*add to neighbor*/
		    int index;
		    if (exit_face==Face.LEFT || exit_face==Face.RIGHT)
			index=(int)part.lc[1];
		    else index=(int)part.lc[0];
		    BoundaryData bc = mesh.boundaryData(exit_face, index);
		    for (int m = 0; m < bc.num_neighbors; m++)
		    {
			if (bc.neighbor[m].containsPos(part.pos))
			{
			    Mesh next = bc.neighbor[m];
			    part.lc = next.XtoL(part.pos);
			    getMeshData(next).addTransferParticle(part);
			    break;
			}
		    }
		    return false;

		default:
		    return false;
	    }
	}

	return true;
    }

    /**
     * ads a new particle
     */
    public boolean addParticle(MeshData md, Particle part)
    {
	if (part.lc == null)
	{
	    Mesh mesh = md.mesh;
	    part.lc = mesh.XtoL(part.pos);
	    
	    /*particles could be added on the plus edge by a source, make sure LC is in range*/
	    if (part.lc[0]>=mesh.ni) part.lc[0]=mesh.ni-1;
	    if (part.lc[1]>=mesh.nj) part.lc[1]=mesh.nj-1;
	}

	/*rewind velocity by -0.5dt*/
	part.dt = -0.5 * Starfish.getDt();

	double ef[] = new double[3];
	double bf[] = new double[3];

	ef[0] = md.Efi.gather(part.lc);
	ef[1] = md.Efj.gather(part.lc);

	bf[0] = md.Bfi.gather(part.lc);
	bf[1] = md.Bfj.gather(part.lc);

	/*update velocity*/
	if (bf[0] == 0 && bf[1] == 0)
	{
	    part.vel[0] += q_over_m * ef[0] * part.dt;
	    part.vel[1] += q_over_m * ef[1] * part.dt;
	} else
	{
	    UpdateVelocityBoris(part, ef, bf);
	}

	part.dt = 0;
	part.id = part_id_counter++;
	
	/*set trace_id, will be set to -1 if no trace*/
	part.trace_id = Starfish.particle_trace_module.getTraceId(part.id);
	
	md.addParticle(part);
	return true;
    }

    /**
     * ads a new particle at the specified position and velocity
     */
    public boolean addParticle(Particle part)
    {
	Mesh mesh = Starfish.domain_module.getMesh(part.pos);
	if (mesh == null)
	{
	    return false;
	}
	MeshData md = getMeshData(mesh);
	return addParticle(md, part);
    }

    /**
     * ads a new particle at the specified position and velocity
     */
    public boolean addParticle(double[] pos, double[] vel)
    {
	Mesh mesh = Starfish.domain_module.getMesh(pos);
	if (mesh == null)
	{
	    return false;
	}

	MeshData md = getMeshData(mesh);

	return addParticle(md, new Particle(pos, vel, spwt0, this));
    }

    private void UpdateVelocityBoris(Particle part, double[] E, double[] B)
    {
	double v_minus[] = new double[3];
	double v_prime[] = new double[3];
	double v_plus[] = new double[3];

	double t[] = new double[3];
	double s[] = new double[3];
	double t_mag2;

	int dim;

	/*t vector*/
	for (dim = 0; dim < 3; dim++)
	{
	    t[dim] = q_over_m * B[dim] * 0.5 * part.dt;
	}

	/*magnitude of t, squared*/
	t_mag2 = t[0] * t[0] + t[1] * t[1] + t[2] * t[2];

	/*s vector*/
	for (dim = 0; dim < 3; dim++)
	{
	    s[dim] = 2 * t[dim] / (1 + t_mag2);
	}

	/*v minus*/
	for (dim = 0; dim < 3; dim++)
	{
	    v_minus[dim] = part.vel[dim] + q_over_m * E[dim] * 0.5 * part.dt;
	}

	/*v prime*/
	double v_minus_cross_t[] = Vector.CrossProduct3(v_minus, t);
	for (dim = 0; dim < 3; dim++)
	{
	    v_prime[dim] = v_minus[dim] + v_minus_cross_t[dim];
	}

	/*v plus*/
	double v_prime_cross_s[] = Vector.CrossProduct3(v_prime, s);
	for (dim = 0; dim < 3; dim++)
	{
	    v_plus[dim] = v_minus[dim] + v_prime_cross_s[dim];
	}

	/*v n+1/2*/
	for (dim = 0; dim < 3; dim++)
	{
	    part.vel[dim] = v_plus[dim] + q_over_m * E[dim] * 0.5 * part.dt;
	}
    }

    /**returns particle with id*/
    public Particle getParticle(long id)
    {
	for (MeshData md:mesh_data)
	    for (int block=0;block<md.particle_block.length;block++)
	    {
	    
		Iterator<Particle> iterator = md.getIterator(block);
		while (iterator.hasNext())
		{
		    Particle part = iterator.next();
		    if (part.id==id) return part;
		}
	}
	return null;
    }

    /*particle definition*/
    static public class Particle
    {

	public double pos[];
	public double vel[];
	public double spwt;
	public double mass;		/*mass of the physical particle*/

	public double lc[];		/*logical coordinate of current position*/

	public double dt;		/*remaining dt to move through*/

	public long id;			/*particle id*/
	public int born_it;
	
	public int trace_id = -1;		/*set to >=0 if particle is being traced*/

	/** copy constructor*/
	public Particle (Particle part) 
	{   
	    pos = new double[3];
	    vel = new double[3];
	    lc = new double[2];
	    for (int i=0;i<3;i++) {pos[i]=part.pos[i];vel[i]=part.vel[i];}
	    for (int i=0;i<2;i++) {lc[i]=part.lc[i];}
	    spwt=part.spwt;
	    mass=part.mass;
	    dt=part.dt;
	    id=part.id;
	    born_it=part.born_it;
	}
	
	//	KineticMaterial mat;	/*parent mat*/
	public Particle(KineticMaterial mat)
	{
	    pos = new double[3];
	    vel = new double[3];
	    lc = null;	    /*null LC will trigger addParticle to compute LC*/
	    dt = 0;
	    spwt = mat.spwt0;
	    mass = mat.mass;
	    born_it = Starfish.getIt();
	}

	public Particle(double spwt, KineticMaterial mat)
	{
	    this(mat);
	    this.spwt = spwt;
	}

	public Particle(double pos[], double vel[], double spwt, KineticMaterial mat)
	{
	    this(spwt, mat);

	    /*copy the first two components of position*/
	    this.pos[0] = pos[0];
	    this.pos[1] = pos[1];
	    this.pos[2] = 0;

	    this.vel[0] = vel[0];
	    this.vel[1] = vel[1];
	    this.vel[2] = vel[2];
	}
    }

    public Iterator<Particle> getIterator(Mesh mesh, int block)
    {
	return getMeshData(mesh).getIterator(block);
    }

    /**
     * @return number of particles
     */
    public long getNp()
    {
	long np = 0;
	for (int m = 0; m < mesh_data.length; m++)
	{
	    np += mesh_data[m].getNp();
	}
	return np;
    }


    /**
     * particle data structure
     */
    public class MeshData 
    {
	MeshData(Mesh mesh)
	{
	    this.mesh = mesh;

	    /*save references*/
	    Efi = Starfish.domain_module.getEfi(mesh);
	    Efj = Starfish.domain_module.getEfj(mesh);
	    Bfi = Starfish.domain_module.getBfi(mesh);
	    Bfj = Starfish.domain_module.getBfj(mesh);
	    
	    num_blocks = Starfish.getNumProcessors();
	    
	    particle_block = new ParticleBlock[num_blocks];
	    transfer_block = new ParticleBlock[num_blocks];
	    
	    /*init particle lists*/
	    for (int i=0;i<particle_block.length;i++)
	    {
		particle_block[i]=new ParticleBlock();
		transfer_block[i]=new ParticleBlock();
	    }    
	}
	
	public int num_blocks;
	Mesh mesh;
	Field2D Efi, Efj;
	Field2D Bfi, Bfj;
	
	ParticleBlock particle_block[];
	ParticleBlock transfer_block[];	/*particles transferred into this mesh from a neighboring one during the transfer*/

	/**add particle to the list, attempting to keep block sizes equal*/
	public void addParticle(Particle part)
	{
	    /*find particle block with fewest particles*/
	    int block=0;
	    int min_count=particle_block[block].particle_list.size();
	    
	    for (int i=1;i<particle_block.length;i++)
		if (particle_block[i].particle_list.size()<min_count) {min_count=particle_block[i].particle_list.size();block=i;}
	    
	    particle_block[block].particle_list.add(part);
	}
	
	/**add particle to the transfers list, attempting to keep block sizes equal*/
	void addTransferParticle(Particle part)
	{
	    /*find particle block with fewest particles*/
	    int block=0;
	    int min_count=transfer_block[block].particle_list.size();
	    
	    for (int i=1;i<particle_block.length;i++)
		if (transfer_block[i].particle_list.size()<min_count) {min_count=transfer_block[i].particle_list.size();block=i;}
	    
	    transfer_block[block].particle_list.add(part);
	}
	
	/** returns number of particles*/
	public long getNp()
	{
	    long count=0;
	    for (int i=0;i<particle_block.length;i++) count+=particle_block[i].particle_list.size();
	    return count;
	}
	
	/** returns particle iterator for the given block*/
	public Iterator<Particle> getIterator(int block) {return particle_block[block].particle_list.iterator();}
	
	/** returns iterator that iterates over all particles in all blocks*/
	public Iterator<Particle> getIterator() {return new BlockIterator(particle_block);}
	
	/** returns transfer particle iterators for the given block*/
	public Iterator<Particle> getTransferIterator(int block) {return transfer_block[block].particle_list.iterator();}	
    }
    public MeshData mesh_data[];
    
    public class ParticleBlock
    {
	public LinkedList<Particle> particle_list = new LinkedList<Particle>();
    }

    public class BlockIterator implements Iterator<Particle>
    {

	ParticleBlock blocks[];
	int b = 0;
	final int num_blocks;
	protected Iterator<Particle> iterator;
	
	BlockIterator (ParticleBlock blocks[])
	{
	    this.blocks = blocks;
	    num_blocks = blocks.length;
	    iterator = blocks[b].particle_list.iterator();
	}
	
	@Override
	public boolean hasNext()
	{
	    if (iterator.hasNext()) return true;
	    
	    /*any more blocks?*/
	    while (b<num_blocks-1)
	    {
		b++;
		iterator = blocks[b].particle_list.iterator();
		if (iterator.hasNext()) return true;
	    }
	    return false;
	}

	@Override
	public Particle next()
	{
	    return iterator.next();
	}

	@Override
	public void remove()
	{
	    iterator.remove();
	}
	
    }
  
    
    public MeshData getMeshData(Mesh mesh)
    {
	for (int m = 0; m < mesh_data.length; m++)
	{
	    if (mesh_data[m].mesh == mesh)
	    {
		return mesh_data[m];
	    }
	}

	Log.warning("Failed to find mesh_data for the specified mesh " + mesh);
	return null;
    }

    void computeT(MeshData md)
    {
	Field2D T = getT(md.mesh);
	Field2D ave_u = new Field2D(md.mesh);
	Field2D ave_v = new Field2D(md.mesh);
	Field2D ave_w = new Field2D(md.mesh);

	Field2D real_count = new Field2D(md.mesh);
	Field2D macro_count = new Field2D(md.mesh);
	
	/*TODO: need vector field!*/
	Iterator<Particle> iterator = md.getIterator();
	while (iterator.hasNext())
	{
	    Particle part = iterator.next();
	    ave_u.scatter(part.lc, part.spwt * part.vel[0]);
	    ave_v.scatter(part.lc, part.spwt * part.vel[1]);
	    ave_w.scatter(part.lc, part.spwt * part.vel[2]);
	    real_count.scatter(part.lc, part.spwt);
	    macro_count.scatter(part.lc, 1);
	}
	
	/*compute average velocity*/
	ave_u.divideByField(real_count);
	ave_v.divideByField(real_count);
	ave_w.divideByField(real_count);

	/*compute temperatures*/
	iterator = md.getIterator();
	while (iterator.hasNext())
	{
	    Particle part = iterator.next();
	    double vd[] = new double[3];
	    vd[0] = ave_u.gather(part.lc);
	    vd[1] = ave_v.gather(part.lc);
	    vd[2] = ave_w.gather(part.lc);

	    double rel = Vector.mag3(Vector.subtract(part.vel, vd));
	    T.scatter(part.lc, part.spwt * rel * rel);    
	}

	/*average*/
	for (int i=0;i<md.mesh.ni;i++)
	    for (int j=0;j<md.mesh.nj;j++)
	    {
		/*need minimum 2 particles for temperature but for better statistics, set to 3*/
		if (macro_count.data[i][j]>2)
		{
		    T.data[i][j]/=real_count.data[i][j];
		}
		else 
		    T.data[i][j] = 0;
	    }

	/*temperature
	 * sqrt{(1/n)(g1^2+g2^2+..gn^2) = sqrt{3kT/m}
	 * (1/n)(g1^2+g2^2+..gn^2) = 3kT/m
	 */
	T.mult(mass / (3.0 * Constants.K));
    }

    /** parser*/
    public static MaterialParser KineticMaterialParser = new MaterialParser()
    {
	@Override
	public Material addMaterial(String name, Element element)
	{
	    /*charge*/
	    double charge = InputParser.getDouble("charge", element);
	    double molwt = InputParser.getDouble("molwt", element);

	    /*kinetic material also need spwt*/
	    double spwt = InputParser.getDouble("spwt", element);

	    Material material = new KineticMaterial(name,molwt,charge,spwt);

	    /*try to get DSMC data*/
	    material.ref_temp = InputParser.getDouble("ref_temp", element,275);
	    material.visc_temp_index = InputParser.getDouble("visc_temp_index",element,1);
	    material.vss_inv = InputParser.getDouble("vss_inv",element,1);
	    material.diam = InputParser.getDouble("diam",element,100e-12);
	
	    /*log*/
	    Log.log("Added KINETIC material '"+name+"'");
	    Log.log("> charge   = "+charge);
	    Log.log("> molwt  = "+molwt+ " (amu)");
	    Log.log("> mass = "+String.format("%.4g (kg)",molwt*Constants.AMU));
	    Log.log("> spwt = "+spwt);
	    return material;
	}
    };    
    
    
}