import Link from 'next/link';

export default function NotFound() {
  return (
    <div className="card empty">
      <h1 className="notFoundTitle">Page not found</h1>
      <p>This view does not exist. <Link href="/">Back to the Overview</Link></p>
    </div>
  );
}
